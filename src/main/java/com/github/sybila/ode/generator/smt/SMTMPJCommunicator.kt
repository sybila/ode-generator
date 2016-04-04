package com.github.sybila.ode.generator.smt

import com.github.daemontus.egholm.logger.lFine
import com.github.daemontus.egholm.logger.lFinest
import com.github.daemontus.egholm.thread.GuardedThread
import com.github.daemontus.jafra.Token
import com.github.sybila.checker.Communicator
import com.github.sybila.checker.IDNode
import com.github.sybila.checker.Job
import com.github.sybila.ode.generator.*
import java.util.logging.Level
import java.util.logging.Logger

//you can initialize the communicator with listeners, otherwise it's not safe, because you can't
//make sure you register a listener before you receive messages
class SMTMPJCommunicator(
        override val id: Int,
        override val size: Int,
        private val order: PartialOrderSet,
        private val comm: AbstractComm,
        private val logger: Logger = Logger.getLogger(SMTMPJCommunicator::class.java.canonicalName).apply {
            this.level = Level.OFF
        },
        private var tokenListener: ((Token) -> Unit)? = null,
        private var jobListener: ((Job<IDNode, SMTColors>) -> Unit)? = null
) : Communicator {

    override var receiveCount: Long = 0L
    override var receiveSize: Long = 0L
    override var sendCount: Long = 0L
    override var sendSize: Long = 0L

    //Command message structure:
    //Token: TOKEN | SENDER | FLAG | COUNT | UNDEFINED
    //Job: JOB | SENDER | SOURCE_ID | TARGET_ID | DATA_SIZE
    //Terminate: TERMINATE | SENDER | UNDEFINED | UNDEFINED | UNDEFINED
    private val inCommandBuffer = IntArray(5)
    private var inDataBuffer = LongArray(100)
    private val outCommandBuffer = IntArray(5)
    private var outDataBuffer = LongArray(100)

    private val mpiListener = GuardedThread() {
        logger.lFinest { "Waiting for message..." }
        comm.receive(inCommandBuffer, 0, inCommandBuffer.size, Type.INT, ANY_SOURCE, COMMAND_TAG)
        receiveCount += 1
        receiveSize += inCommandBuffer.size
        logger.lFinest { "Got message, type: ${inCommandBuffer[0]}..." }
        while (inCommandBuffer[0] != TERMINATE) {
            if (inCommandBuffer[0] == TOKEN) {
                logger.lFinest { "Starting token listener" }
                tokenListener?.invoke(inCommandBuffer.toToken()) ?: throw IllegalStateException("Token listener not provided")
                logger.lFinest { "Token listener finished" }
            } else if (inCommandBuffer[0] == JOB) {
                logger.lFinest { "Starting job listener" }
                jobListener?.invoke(inCommandBuffer.toJob()) ?: throw IllegalStateException("Job listener not provided")
                logger.lFinest { "Job listener finished" }
            }
            logger.lFinest { "Waiting for message..." }
            comm.receive(inCommandBuffer, 0, inCommandBuffer.size, Type.INT, ANY_SOURCE, 0)
            receiveCount += 1
            receiveSize += inCommandBuffer.size
            logger.lFinest { "Got message, type: ${inCommandBuffer[0]}..." }
        }
    }.apply { this.thread.start() }

    private fun receiveColors(sender: Int, bufferSize: Int): SMTColors {
        while (bufferSize > inDataBuffer.size) { //increase buffer size if needed
            inDataBuffer = LongArray(inDataBuffer.size * 2)
        }
        //we need sender to ensure we don't mix data and commands from different senders
        logger.lFinest { "Waiting to receive colors..." }
        comm.receive(inDataBuffer, 0, bufferSize, Type.LONG, sender, DATA_TAG)
        receiveCount += 1
        receiveSize += size
        logger.lFinest { "Colors received." }
        return inDataBuffer.readSMTColors(order)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <M : Any> addListener(messageClass: Class<M>, onTask: (M) -> Unit) {

        logger.lFine { "Add listener: $messageClass" }
        when (messageClass) {
            Token::class.java -> synchronized(this) {
                if (tokenListener != null) {
                    throw IllegalStateException("Replacing already present listener: $id, $messageClass")
                } else {
                    tokenListener = onTask as (Token) -> Unit
                }
            }
            Job::class.java -> synchronized(this) {
                if (jobListener != null) {
                    throw IllegalStateException("Replacing already present listener: $id, $messageClass")
                } else {
                    jobListener = onTask as (Job<IDNode, SMTColors>) -> Unit
                }
            }
            else -> throw IllegalArgumentException("This communicator can't send classes of type: $messageClass")
        }
    }

    override fun removeListener(messageClass: Class<*>) {
        logger.lFine { "Remove listener: $messageClass" }
        when (messageClass) {
            Token::class.java -> synchronized(this) {
                if (tokenListener == null) {
                    throw IllegalStateException("Removing non existent listener: $id, $messageClass")
                } else tokenListener = null
            }
            Job::class.java -> synchronized(this) {
                if (jobListener == null) {
                    throw IllegalStateException("Removing non existent listener: $id, $messageClass")
                } else jobListener = null
            }
            else -> throw IllegalStateException("Removing non existent listener: $id, $messageClass")
        }
    }

    override fun send(dest: Int, message: Any) {
        if (dest == id) throw IllegalArgumentException("Can't send message to yourself")
        if (message.javaClass == Token::class.java) {
            val token = message as Token
            token.serialize(outCommandBuffer)
            logger.lFinest { "Sending token" }
            comm.send(outCommandBuffer, 0, outCommandBuffer.size, Type.INT, dest, COMMAND_TAG)
            sendCount += 1
            sendSize += outCommandBuffer.size
        } else if (message.javaClass == Job::class.java) {
            //send command info
            @Suppress("UNCHECKED_CAST") //It's ok, this won't be used outside this module!
            val job = message as Job<IDNode, SMTColors>
            val bufferSize = job.colors.calculateBufferSize()
            job.serialize(outCommandBuffer, bufferSize)
            logger.lFinest { "Sending job info" }
            comm.send(outCommandBuffer, 0, outCommandBuffer.size, Type.INT, dest, COMMAND_TAG)
            sendCount += 1
            sendSize += outCommandBuffer.size

            //ensure data buffer capacity
            while (bufferSize > outDataBuffer.size) {
                outDataBuffer = LongArray(outDataBuffer.size * 2)
            }

            //send color data
            job.colors.serialize(outDataBuffer)
            logger.lFinest { "Sending colors" }
            comm.send(outDataBuffer, 0, bufferSize, Type.LONG, dest, DATA_TAG)
            sendCount += 1
            sendSize += bufferSize
        } else {
            throw IllegalArgumentException("Cannot send message: $message to $dest")
        }
    }

    override fun close() {
        synchronized(this) {
            if (tokenListener != null || jobListener != null) {
                throw IllegalStateException("Someone is still listening! $tokenListener $jobListener")
            }
        }
        outCommandBuffer[0] = TERMINATE
        outCommandBuffer[1] = id
        //there is a bug in MPJ where you can't send messages to yourself in MPI configuration (you have to use hybrid)
        //so in order to circumvent this, each node terminates it's successor. - this is valid because
        //termination detection has to be ensured before calling close anyway.
        logger.lFinest { "Sending termination token" }
        comm.send(outCommandBuffer, 0, outCommandBuffer.size, Type.INT, (id + 1) % size, COMMAND_TAG)
        logger.lFinest { "Waiting for termination..." }
        mpiListener.join()
    }


    /* Data conversion functions */

    private fun IntArray.toToken(): Token = Token(this[2], this[3])

    private fun IntArray.toJob(): Job<IDNode, SMTColors> = Job(
            source = IDNode(this[2]), target = IDNode(this[3]), colors = receiveColors(this[1], this[4])
    )

    private fun Token.serialize(to: IntArray) {
        to[0] = TOKEN; to[1] = id; to[2] = this.flag; to[3] = this.count
    }

    private fun Job<IDNode, SMTColors>.serialize(to: IntArray, bufferSize: Int) {
        to[0] = JOB; to[1] = id; to[2] = this.source.id; to[3] = this.target.id; to[4] = bufferSize
    }

}