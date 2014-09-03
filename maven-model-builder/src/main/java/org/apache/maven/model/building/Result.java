package org.apache.maven.model.building;

import static com.google.common.base.Predicates.in;
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.transform;
import static java.util.Collections.singleton;
import static java.util.EnumSet.of;
import static org.apache.maven.model.building.ModelProblem.Severity.ERROR;
import static org.apache.maven.model.building.ModelProblem.Severity.FATAL;

import java.util.Collections;

import org.apache.maven.model.building.ModelProblem.Severity;

import com.google.common.base.Function;
import com.google.common.base.Predicates;

/**
 * There are various forms of results that are represented by this class:
 * <ol>
 * <li>success - in which case only the model field is set
 * <li>success with warnings - model field + non-error model problems
 * <li>error - no model, but diagnostics
 * <li>error - (partial) model and diagnostics
 * </ol>
 * Could encode these variants as subclasses, but kept in one for now
 * 
 * @author bbusjaeger
 * @param <T>
 */
public class Result<T> {

    public static <T> Result<T> success(T model) {
        return newResult(model, Collections.<ModelProblem> emptyList());
    }

    public static <T> Result<T> error(Iterable<? extends ModelProblem> problems) {
        return error(null, problems);
    }

    public static <T> Result<T> error(T model, Iterable<? extends ModelProblem> problems) {
        return newResult(true, model, problems);
    }

    public static <T> Result<T> newResult(T model, Iterable<? extends ModelProblem> problems) {
        return newResult(hasErrors(problems), model, problems);
    }

    public static <T> Result<T> newResult(boolean hasErrors, T model, Iterable<? extends ModelProblem> problems) {
        return new Result<T>(hasErrors, model, problems);
    }

    public static <T> Result<T> newResult(Result<T> result, ModelProblem problem) {
        return newResult(result, singleton(problem));
    }

    public static <T> Result<T> newResult(Result<T> result, Iterable<? extends ModelProblem> problems) {
        return new Result<T>(result.hasErrors() || hasErrors(problems), result.get(), concat(result.getProblems(),
                problems));
    }

    public static <T> Result<Iterable<? extends T>> newResultSet(Iterable<? extends Result<T>> results) {
        return new Result<Iterable<? extends T>>(any(transform(results, hasErrors), Predicates.equalTo(true)),
                transform(results, Result.<T> getF()), concat(transform(results, getProblems)));
    }

    private static boolean hasErrors(Iterable<? extends ModelProblem> problems) {
        return any(transform(problems, getSeverity), in(of(ERROR, FATAL)));
    }

    static final Function<Result<?>, Iterable<? extends ModelProblem>> getProblems = new Function<Result<?>, Iterable<? extends ModelProblem>>() {
        @Override
        public Iterable<? extends ModelProblem> apply(Result<?> input) {
            return input.getProblems();
        }
    };

    static final Function<Result<?>, Boolean> hasErrors = new Function<Result<?>, Boolean>() {

        @Override
        public Boolean apply(Result<?> input) {
            return input.hasErrors();
        }
    };

    private static final Function<ModelProblem, Severity> getSeverity = new Function<ModelProblem, ModelProblem.Severity>() {

        @Override
        public Severity apply(ModelProblem input) {
            return input.getSeverity();
        }
    };

    private static <T> Function<Result<T>, T> getF() {
        return new Function<Result<T>, T>() {

            @Override
            public T apply(Result<T> input) {
                return input.get();
            }
        };
    }

    private final boolean errors;
    private final T model;
    private final Iterable<? extends ModelProblem> problems;

    Result(boolean errors, T model, Iterable<? extends ModelProblem> problems) {
        this.errors = errors;
        this.model = model;
        this.problems = problems;
    }

    public Iterable<? extends ModelProblem> getProblems() {
        return problems;
    }

    public T get() {
        return model;
    }

    public boolean hasErrors() {
        return errors;
    }

}