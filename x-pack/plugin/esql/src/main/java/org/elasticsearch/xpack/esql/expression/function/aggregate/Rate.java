/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.aggregate;

import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.compute.aggregation.AggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.RateDoubleAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.RateIntAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.RateLongAggregatorFunctionSupplier;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.OptionalArgument;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.planner.ToAggregator;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.FIRST;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.SECOND;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isType;
import static org.elasticsearch.xpack.esql.core.util.CollectionUtils.nullSafeList;

public class Rate extends AggregateFunction implements OptionalArgument, ToAggregator {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(Expression.class, "Rate", Rate::new);
    private static final TimeValue DEFAULT_UNIT = TimeValue.timeValueSeconds(1);

    private final Expression timestamp;
    private final Expression unit;

    @FunctionInfo(
        returnType = { "double" },
        description = "compute the rate of a counter field. Available in METRICS command only",
        isAggregation = true
    )
    public Rate(
        Source source,
        @Param(name = "field", type = { "counter_long|counter_integer|counter_double" }, description = "counter field") Expression field,
        Expression timestamp,
        @Param(optional = true, name = "unit", type = { "time_duration" }, description = "the unit") Expression unit
    ) {
        this(source, field, Literal.TRUE, timestamp, unit);
    }

    // compatibility constructor used when reading from the stream
    private Rate(Source source, Expression field, Expression filter, List<Expression> children) {
        this(source, field, filter, children.get(0), children.size() > 1 ? children.get(1) : null);
    }

    private Rate(Source source, Expression field, Expression filter, Expression timestamp, Expression unit) {
        super(source, field, filter, unit != null ? List.of(timestamp, unit) : List.of(timestamp));
        this.timestamp = timestamp;
        this.unit = unit;
    }

    public Rate(StreamInput in) throws IOException {
        this(
            Source.readFrom((PlanStreamInput) in),
            in.readNamedWriteable(Expression.class),
            in.getTransportVersion().onOrAfter(TransportVersions.V_8_16_0) ? in.readNamedWriteable(Expression.class) : Literal.TRUE,
            in.getTransportVersion().onOrAfter(TransportVersions.V_8_16_0)
                ? in.readNamedWriteableCollectionAsList(Expression.class)
                : nullSafeList(in.readNamedWriteable(Expression.class), in.readOptionalNamedWriteable(Expression.class))
        );
    }

    @Override
    protected void deprecatedWriteParams(StreamOutput out) throws IOException {
        out.writeNamedWriteable(timestamp);
        out.writeOptionalNamedWriteable(unit);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    public static Rate withUnresolvedTimestamp(Source source, Expression field, Expression unit) {
        return new Rate(source, field, new UnresolvedAttribute(source, "@timestamp"), unit);
    }

    @Override
    protected NodeInfo<Rate> info() {
        return NodeInfo.create(this, Rate::new, field(), timestamp, unit);
    }

    @Override
    public Rate replaceChildren(List<Expression> newChildren) {
        if (unit != null) {
            if (newChildren.size() == 4) {
                return new Rate(source(), newChildren.get(0), newChildren.get(1), newChildren.get(2), newChildren.get(3));
            }
            assert false : "expected 4 children for field, filter, @timestamp, and unit; got " + newChildren;
            throw new IllegalArgumentException("expected 4 children for field, filter, @timestamp, and unit; got " + newChildren);
        } else {
            if (newChildren.size() == 3) {
                return new Rate(source(), newChildren.get(0), newChildren.get(1), newChildren.get(2), null);
            }
            assert false : "expected 3 children for field, filter and @timestamp; got " + newChildren;
            throw new IllegalArgumentException("expected 3 children for field, filter and @timestamp; got " + newChildren);
        }
    }

    @Override
    public Rate withFilter(Expression filter) {
        return new Rate(source(), field(), filter, timestamp, unit);
    }

    @Override
    public DataType dataType() {
        return DataType.DOUBLE;
    }

    @Override
    protected TypeResolution resolveType() {
        TypeResolution resolution = isType(
            field(),
            dt -> DataType.isCounter(dt),
            sourceText(),
            FIRST,
            "counter_long",
            "counter_integer",
            "counter_double"
        );
        if (unit != null) {
            resolution = resolution.and(
                isType(unit, dt -> dt.isWholeNumber() || DataType.isTemporalAmount(dt), sourceText(), SECOND, "time_duration")
            );
        }
        return resolution;
    }

    long unitInMillis() {
        if (unit == null) {
            return DEFAULT_UNIT.millis();
        }
        if (unit.foldable() == false) {
            throw new IllegalArgumentException("function [" + sourceText() + "] has invalid unit [" + unit.sourceText() + "]");
        }
        final Object foldValue;
        try {
            foldValue = unit.fold(FoldContext.small() /* TODO remove me */);
        } catch (Exception e) {
            throw new IllegalArgumentException("function [" + sourceText() + "] has invalid unit [" + unit.sourceText() + "]");
        }
        if (foldValue instanceof Duration duration) {
            return duration.toMillis();
        }
        throw new IllegalArgumentException("function [" + sourceText() + "] has invalid unit [" + unit.sourceText() + "]");
    }

    @Override
    public AggregatorFunctionSupplier supplier(List<Integer> inputChannels) {
        if (inputChannels.size() != 2 && inputChannels.size() != 3) {
            throw new IllegalArgumentException("rate requires two for raw input or three channels for partial input; got " + inputChannels);
        }
        final long unitInMillis = unitInMillis();
        final DataType type = field().dataType();
        return switch (type) {
            case COUNTER_LONG -> new RateLongAggregatorFunctionSupplier(inputChannels, unitInMillis);
            case COUNTER_INTEGER -> new RateIntAggregatorFunctionSupplier(inputChannels, unitInMillis);
            case COUNTER_DOUBLE -> new RateDoubleAggregatorFunctionSupplier(inputChannels, unitInMillis);
            default -> throw EsqlIllegalArgumentException.illegalDataType(type);
        };
    }

    @Override
    public String toString() {
        if (unit != null) {
            return "rate(" + field() + "," + unit + ")";
        } else {
            return "rate(" + field() + ")";
        }
    }

    Expression timestamp() {
        return timestamp;
    }

    Expression unit() {
        return unit;
    }
}
