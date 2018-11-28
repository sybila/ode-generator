package com.github.sybila.ode.generator.v2;

import com.github.sybila.ode.generator.NodeEncoder;
import com.github.sybila.ode.model.Evaluable;
import com.github.sybila.ode.model.ModelApproximationKt;
import com.github.sybila.ode.model.OdeModel;
import com.github.sybila.ode.model.OdeModel.Variable;
import com.github.sybila.ode.model.Parser;
import com.github.sybila.ode.model.Summand;
import kotlin.collections.CollectionsKt;
import kotlin.collections.IntIterator;
import kotlin.ranges.RangesKt;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class SimpleOdeTransitionSystem implements TransitionSystem<Integer, Boolean> {

    private final OdeModel model;
    private final NodeEncoder encoder;
    private final Integer dimensions;
    private Integer stateCount;
    private List<Boolean> facetColors;
    private List<List<Summand>> equations = new ArrayList<>();
    private List<List<Double>> thresholds = new ArrayList<>();
    private int[] vertexMasks = getVertexMasks();

    private Integer PositiveIn = 0;
    private Integer PositiveOut = 1;
    private Integer NegativeIn = 2;
    private Integer NegativeOut = 3;

    public SimpleOdeTransitionSystem(OdeModel model) {
        this.model = model;
        this.encoder = new NodeEncoder(model);
        this.dimensions = model.getVariables().size();
        this.stateCount = getStateCount();
        this.facetColors = new ArrayList<>();

        for (int i = 0; i < stateCount * dimensions * 4; i++) {
            this.facetColors.add(null);
        }

        for (Variable var: model.getVariables()) {
            this.equations.add(var.getEquation());
            this.thresholds.add(var.getThresholds());
        }
    }

    private int[] getVertexMasks() {
        Iterable receiver = RangesKt.until(0, dimensions);
        Object accumulator = CollectionsKt.listOf(0);

        Collection destination$iv$iv;
        for(Iterator var5 = receiver.iterator(); var5.hasNext(); accumulator = (List)destination$iv$iv) {
            int element$iv = ((IntIterator)var5).nextInt();
            receiver = (Iterable) accumulator;
            destination$iv$iv = (Collection)(new ArrayList(CollectionsKt.collectionSizeOrDefault(receiver, 10)));
            Iterator var12 = receiver.iterator();

            Object element$iv$iv;
            int it;
            while(var12.hasNext()) {
                element$iv$iv = var12.next();
                it = ((Number)element$iv$iv).intValue();
                Integer var16 = it << 1;
                destination$iv$iv.add(var16);
            }

            receiver = (Iterable)((List)destination$iv$iv);
            destination$iv$iv = (Collection)(new ArrayList());
            var12 = receiver.iterator();

            while(var12.hasNext()) {
                element$iv$iv = var12.next();
                it = ((Number)element$iv$iv).intValue();
                Iterable list$iv$iv = (Iterable)CollectionsKt.listOf(new Integer[]{it, it | 1});
                CollectionsKt.addAll(destination$iv$iv, list$iv$iv);
            }
        }

        return CollectionsKt.toIntArray((Collection) accumulator);
    }

    private Integer getStateCount() {
        Integer result = 1;
        for (Variable var: model.getVariables()) {
            result = result * (var.getThresholds().size() - 1);
        }
        return result;
    }

    @NotNull
    @Override
    public List<Integer> successors(@NotNull Integer from) {
        return getStep(from, true);
    }

    @NotNull
    @Override
    public List<Integer> predecessors(@NotNull Integer from) {
       return getStep(from, false);
    }


    private List<Integer> getStep(Integer from, Boolean successors) {
        List<Integer> result = new ArrayList<>();
        for (int dim = 0; dim < model.getVariables().size(); dim++) {
            String dimName = model.getVariables().get(dim).getName();
            Boolean timeFlow = true;

            Boolean positiveIn = getFacetColors(from, dim, timeFlow ? PositiveIn : PositiveOut);
            Boolean negativeIn = getFacetColors(from, dim, timeFlow ? NegativeIn : NegativeOut);
            Boolean positiveOut = getFacetColors(from, dim, timeFlow ? PositiveOut : PositiveIn);
            Boolean negativeOut = getFacetColors(from, dim, timeFlow ? NegativeOut : NegativeIn);

            Integer higherNode = encoder.higherNode(from, dim);
            Boolean colors = successors ? positiveOut : positiveIn;
            if (higherNode != null && colors) {
                result.add(higherNode);
            }

            Integer lowerNode = encoder.lowerNode(from, dim);
            colors = successors ? negativeOut : negativeIn;
            if (higherNode != null && colors) {
                result.add(lowerNode);
            }

        }
        return result;
    }


    private Integer facetIndex(Integer from, Integer dimension, Integer orientation) {
        return from + (stateCount * dimension) + (stateCount * dimensions * orientation);
    }

    private Boolean getFacetColors(Integer from, Integer dimension, Integer orientation) {
        //need to iterate over vertices
        Integer facetIndex = facetIndex(from, dimension, orientation);
        /*
        val colors = vertexMasks
                .filter { it.shr(dimension).and(1) == positiveFacet }
                    .fold(ff) { a, mask ->
                val vertex = encoder.nodeVertex(from, mask)
            getVertexColor(vertex, dimension, positiveDerivation)?.let { a or it } ?: a
        }
        //val colors = tt
        encoder.nodeVertex(from, )
        */
        //Arrays.stream(vertexMasks).filter(i -> (i >> dimension & 1) == positiveFacet)

        Boolean value = facetColors.get(facetIndex);
        if (value != null) {
            return value;
        } else {
            Integer positiveFacet = (orientation.equals(PositiveIn) || orientation.equals(PositiveOut)) ? 1 : 0;
            Boolean positiveDerivation = orientation.equals(PositiveOut) || orientation.equals(NegativeIn);
            value = Arrays.stream(vertexMasks)
                    .filter(e -> (e >> dimension & 1) == positiveFacet)
                    .reduce((a, mask) -> {
                        Integer vertex =  encoder.nodeVertex(from, mask);
                        getVertexColor(vertex, dimension, positiveDerivation);
                    });
        }
        return value;
    }

    private Boolean getVertexColor(Integer vertex, Integer dimension, Boolean positive) {

        Double derivationValue = 0.0;

        for (Summand summand : equations.get(dimension)) {
            double partialSum = summand.getConstant();
            for (Integer v : summand.getVariableIndices()) {
                partialSum *= thresholds.get(v).get(encoder.vertexCoordinate(vertex, v));
            }
            if (partialSum != 0.0) {
                for (Evaluable function : summand.getEvaluable()) {
                    Integer index = function.getVarIndex();
                    partialSum *= function.invoke(thresholds.get(index).get(encoder.vertexCoordinate(vertex, index)));
                }
            }
            derivationValue += partialSum;
        }

        return derivationValue == 0.0 ? null : derivationValue > 0 == positive;
    }



    @NotNull
    @Override
    public Boolean transitionParameters(@NotNull Integer source, @NotNull Integer target) {
        return null;
    }

    public static void main(String[] args) {
        Parser modelParser = new Parser();
        OdeModel model = modelParser.parse(new File("model.txt"));
        OdeModel modelWithThresholds = ModelApproximationKt.computeApproximation(model, false, true);
        SimpleOdeTransitionSystem simpleOdeTransitionSystem = new SimpleOdeTransitionSystem(modelWithThresholds);
    }

}
