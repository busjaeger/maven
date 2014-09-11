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

    /**
     * Success without warnings
     * 
     * @param model
     * @return
     */
    public static <T> Result<T> success(T model) {
        return success(model, Collections.<ModelProblem> emptyList());
    }

    /**
     * Success with warnings
     * 
     * @param model
     * @param problems
     * @return
     */
    public static <T> Result<T> success(T model, Iterable<? extends ModelProblem> problems) {
        assert !hasErrors(problems);
        return new Result<T>(true, model, problems);
    }

    /**
     * Error with problems describing the cause
     *
     * @param problems
     * @return
     */
    public static <T> Result<T> error(Iterable<? extends ModelProblem> problems) {
        return error(null, problems);
    }

    /**
     * Error with partial result and problems describing the cause
     *
     * @param model
     * @param problems
     * @return
     */
    public static <T> Result<T> error(T model, Iterable<? extends ModelProblem> problems) {
        return new Result<T>(true, model, problems);
    }

    /**
     * New result - determine whether error or success by checking problems for errors
     * 
     * @param model
     * @param problems
     * @return
     */
    public static <T> Result<T> newResult(T model, Iterable<? extends ModelProblem> problems) {
        return new Result<T>(hasErrors(problems), model, problems);
    }

    /**
     * New result consisting of given result and new problem. Convenience for newResult(result.get(),
     * concat(result.getProblems(),problems)).
     * 
     * @param result
     * @param problem
     * @return
     */
    public static <T> Result<T> addProblem(Result<T> result, ModelProblem problem) {
        return addProblems(result, singleton(problem));
    }

    /**
     * New result that includes the given
     *
     * @param result
     * @param problems
     * @return
     */
    public static <T> Result<T> addProblems(Result<T> result, Iterable<? extends ModelProblem> problems) {
        return new Result<T>(result.hasErrors() || hasErrors(problems), result.get(), concat(result.getProblems(),
                problems));
    }

    /**
     * Turns the given results into a single result by combining problems and models into single collection.
     * 
     * @param results
     * @return
     */
    public static <T> Result<Iterable<T>> newResultSet(Iterable<? extends Result<? extends T>> results) {
        final boolean hasErrors = any(transform(results, new Function<Result<?>, Boolean>() {
            @Override
            public Boolean apply(Result<?> input) {
                return input.hasErrors();
            }
        }), Predicates.equalTo(true));
        final Iterable<T> models = transform(results, new Function<Result<? extends T>, T>() {
            @Override
            public T apply(Result<? extends T> input) {
                return input.get();
            }
        });
        final Iterable<ModelProblem> problems = concat(transform(results,
                new Function<Result<?>, Iterable<? extends ModelProblem>>() {
                    @Override
                    public Iterable<? extends ModelProblem> apply(Result<?> input) {
                        return input.getProblems();
                    }
                }));
        return new Result<Iterable<T>>(hasErrors, models, problems);
    }

    // helper to determine if problems contain error
    private static boolean hasErrors(Iterable<? extends ModelProblem> problems) {
        return any(transform(problems, new Function<ModelProblem, Severity>() {
            @Override
            public Severity apply(ModelProblem input) {
                return input.getSeverity();
            }
        }), in(of(ERROR, FATAL)));
    }

    /**
     * Class definition
     */

    private final boolean errors;
    private final T value;
    private final Iterable<? extends ModelProblem> problems;

    private Result(boolean errors, T model, Iterable<? extends ModelProblem> problems) {
        this.errors = errors;
        this.value = model;
        this.problems = problems;
    }

    public Iterable<? extends ModelProblem> getProblems() {
        return problems;
    }

    public T get() {
        return value;
    }

    public boolean hasErrors() {
        return errors;
    }

}