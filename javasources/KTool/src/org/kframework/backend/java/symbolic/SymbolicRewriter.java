package org.kframework.backend.java.symbolic;

import org.kframework.backend.java.builtins.IntToken;
import org.kframework.backend.java.indexing.BottomIndex;
import org.kframework.backend.java.indexing.FreezerIndex;
import org.kframework.backend.java.indexing.Index;
import org.kframework.backend.java.indexing.IndexingPair;
import org.kframework.backend.java.indexing.KLabelIndex;
import org.kframework.backend.java.indexing.TokenIndex;
import org.kframework.backend.java.indexing.TopIndex;
import org.kframework.backend.java.builtins.BoolToken;
import org.kframework.backend.java.kil.Cell;
import org.kframework.backend.java.kil.ConstrainedTerm;import org.kframework.backend.java.kil.Definition;
import org.kframework.backend.java.kil.KItem;
import org.kframework.backend.java.kil.KLabelConstant;
import org.kframework.backend.java.kil.Kind;
import org.kframework.backend.java.kil.Rule;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.Variable;
import org.kframework.kil.loader.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;


/**
 *
 *
 * @author AndreiS
 */
public class SymbolicRewriter {

    private final Context context;
    private final Definition definition;
    private final Stopwatch stopwatch = new Stopwatch();
    private final Stopwatch ruleStopwatch = new Stopwatch();
    private final Map<IndexingPair, Set<Rule>> ruleTable;
    private final List<ConstrainedTerm> results = new ArrayList<ConstrainedTerm>();

	public SymbolicRewriter(Definition definition, Context context) {
        this.definition = definition;
        this.context = context;

        ruleTable = new HashMap<IndexingPair, Set<Rule>>();

        Set<Index> indices = new HashSet<Index>();
        indices.add(TopIndex.TOP);
        indices.add(BottomIndex.BOTTOM);
        for (KLabelConstant kLabel : definition.kLabels()) {
            indices.add(new KLabelIndex(kLabel));
        }
        for (KLabelConstant frozenKLabel : definition.frozenKLabels()) {
            indices.add(new FreezerIndex(frozenKLabel, -1));
            for (int i = 0; i < frozenKLabel.productions().get(0).getArity(); ++i) {
                indices.add(new FreezerIndex(frozenKLabel, i));
            }
        }
        for (String sort : Definition.TOKEN_SORTS) {
            indices.add(new TokenIndex(sort));
        }

        for (Index first : indices) {
            for (Index second : indices) {
                IndexingPair pair = new IndexingPair(first, second);

                ImmutableSet.Builder<Rule> builder = ImmutableSet.builder();
                for (Rule rule : definition.rules()) {
                    if (pair.isUnifiable(rule.indexingPair())) {
                        builder.add(rule);
                    }
                }

                ImmutableSet<Rule> rules = builder.build();
                if (!rules.isEmpty()) {
                    ruleTable.put(pair, rules);
                }
            }
        }
	}

    public ConstrainedTerm rewrite(ConstrainedTerm constrainedTerm, int bound) {
        stopwatch.start();

        for (int i = 0; i != bound; ++i) {
            /* get the first solution */
            computeRewriteStep(constrainedTerm);
            ConstrainedTerm result = getTransition(0);
            if (result != null) {
                constrainedTerm = result;
            } else {
                break;
            }
        }

        stopwatch.stop();
        System.err.println("[" + stopwatch +"]");

        return constrainedTerm;
    }

    public ConstrainedTerm rewrite(ConstrainedTerm constrainedTerm) {
        return rewrite(constrainedTerm, -1);
    }

    private Set<Rule> getRules(Term term) {
        final List<Term> contents = new ArrayList<Term>();
        term.accept(new BottomUpVisitor() {
            @Override
            public void visit(Cell cell) {
                if (cell.contentKind() == Kind.CELL_COLLECTION) {
                    super.visit(cell);
                } else if (cell.getLabel().equals("k")) {
                    contents.add(cell.getContent());
                }
            }
        });

        Set<Rule> rules = new HashSet<Rule>();
        for (Term content : contents) {
            IndexingPair pair = IndexingPair.getIndexingPair(content);
            if (ruleTable.get(pair) != null) {
                rules.addAll(ruleTable.get(pair));
            }
        }

        return rules;
    }

    private ConstrainedTerm getTransition(int n) {
        return n < results.size() ? results.get(n) : null;
    }

    private void computeRewriteStep(ConstrainedTerm constrainedTerm) {
        results.clear();

        for (Rule rule : getRules(constrainedTerm.term())) {
            ruleStopwatch.reset();
            ruleStopwatch.start();

            SymbolicConstraint leftHandSideConstraint = new SymbolicConstraint(context);
            //leftHandSideConstraint.addAll(rule.condition());
            if (rule.condition() instanceof KItem && ((KItem) rule.condition()).kLabel().toString().equals("'fresh(_)")) {
                leftHandSideConstraint.add(((KItem) rule.condition()).kList().get(0), IntToken.fresh());
            } else {
                leftHandSideConstraint.add(rule.condition(), BoolToken.TRUE);
            }

            ConstrainedTerm leftHandSide = new ConstrainedTerm(
                    rule.leftHandSide(),
                    rule.lookups(),
                    leftHandSideConstraint);

            for (SymbolicConstraint constraint1 : constrainedTerm.unify(leftHandSide, context)) {
                /* rename rule variables in the constraints */
                Map<Variable, Variable> freshSubstitution = constraint1.rename(rule.variableSet());

                Term result = rule.rightHandSide();
                /* rename rule variables in the rule RHS */
                result = result.substitute(freshSubstitution, context);
                /* apply the constraints substitution on the rule RHS */
                result = result.substitute(constraint1.substitution(), context);
                /* evaluate pending functions in the rule RHS */
                result = result.evaluate(context);
                /* eliminate anonymous variables */
                constraint1.eliminateAnonymousVariables();

                /*
                System.err.println("rule \n\t" + rule);
                System.err.println("result constraint\n\t" + constraint1);
                System.err.println("result term\n\t" + result);
                System.err.println("============================================================");

                ruleStopwatch.stop();
                System.err.println("### " + ruleStopwatch);
                */

                /* compute all results */
                results.add(new ConstrainedTerm(result, constraint1, context));
            }
        }
    }

    /**
     * Apply a specification rule
     */
    private ConstrainedTerm applyRule(ConstrainedTerm constrainedTerm, List<Rule> rules) {
        for (Rule rule : rules) {
            ruleStopwatch.reset();
            ruleStopwatch.start();

            SymbolicConstraint constraint = constrainedTerm.match(
                    (ConstrainedTerm) rule.leftHandSide(),
                    context);
            if (constraint == null) {
                continue;
            }

            /* rename rule variables in the constraints */
            Map<Variable, Variable> freshSubstitution = constraint.rename(rule.variableSet());

            Term result = rule.rightHandSide();
            /* rename rule variables in the rule RHS */
            result = result.substitute(freshSubstitution, context);
            /* apply the constraints substitution on the rule RHS */
            result = result.substitute(constraint.substitution(), context);
            /* evaluate pending functions in the rule RHS */
            result = result.evaluate(context);
            /* eliminate anonymous variables */
            constraint.eliminateAnonymousVariables();

            /* return first solution */
            return new ConstrainedTerm(result, constraint, context);
        }

        return null;
    }

    public List<ConstrainedTerm> search(
            ConstrainedTerm initialTerm,
            ConstrainedTerm targetTerm,
            List<Rule> rules) {
        stopwatch.start();

        List<ConstrainedTerm> searchResults = new ArrayList<ConstrainedTerm>();
        Set<ConstrainedTerm> visited = new HashSet<ConstrainedTerm>();
        List<ConstrainedTerm> queue = new ArrayList<ConstrainedTerm>();
        List<ConstrainedTerm> nextQueue = new ArrayList<ConstrainedTerm>();

        visited.add(initialTerm);
        queue.add(initialTerm);
        while (!queue.isEmpty()) {
            for (ConstrainedTerm term : queue) {
                computeRewriteStep(term);

                if (results.isEmpty()) {
                    /* final term */
                    searchResults.add(term);
                }


                for (int i = 0; getTransition(i) != null; ++i) {
                    if (visited.add(getTransition(i))) {
                        // if getTransition(i) not implies targetTerm
                        nextQueue.add(getTransition(i));
                    }
                }
            }

            /* swap the queues */
            List<ConstrainedTerm> temp;
            temp = queue;
            queue = nextQueue;
            nextQueue = temp;
            nextQueue.clear();

            /*
            for (ConstrainedTerm result : queue) {
                System.err.println(result);
            }
            System.err.println("============================================================");
            */
        }


        stopwatch.stop();
        System.err.println("[" + visited.size() + "states, " + stopwatch +"]");

        return searchResults;
    }

    public List<ConstrainedTerm> prove(List<Rule> rules) {
        stopwatch.start();

        List<ConstrainedTerm> proofResults = new ArrayList<ConstrainedTerm>();
        for (Rule rule : rules) {
            /* rename rule variables */
            Map<Variable, Variable> freshSubstitution = Variable.getFreshSubstitution(rule.variableSet());

            SymbolicConstraint sideConstraint = new SymbolicConstraint(context);
            sideConstraint.add(rule.condition(), BoolToken.TRUE);
            ConstrainedTerm initialTerm = new ConstrainedTerm(
                    rule.leftHandSide().substitute(freshSubstitution, context),
                    rule.lookups().substitute(freshSubstitution, context),
                    sideConstraint.substitute(freshSubstitution, context));

            ConstrainedTerm targetTerm = new ConstrainedTerm(
                    rule.rightHandSide().substitute(freshSubstitution, context),
                    context);

            proofResults.addAll(proveRule(initialTerm, targetTerm, rules));
        }

        stopwatch.stop();
        System.err.println("[" + stopwatch + "]");

        return proofResults;
    }

    private List<ConstrainedTerm> proveRule(
            ConstrainedTerm initialTerm,
            ConstrainedTerm targetTerm,
            List<Rule> rules) {
        List<ConstrainedTerm> proofResults = new ArrayList<ConstrainedTerm>();
        Set<ConstrainedTerm> visited = new HashSet<ConstrainedTerm>();
        List<ConstrainedTerm> queue = new ArrayList<ConstrainedTerm>();
        List<ConstrainedTerm> nextQueue = new ArrayList<ConstrainedTerm>();

        visited.add(initialTerm);
        queue.add(initialTerm);
        boolean guarded = false;
        while (!queue.isEmpty()) {
            for (ConstrainedTerm term : queue) {
                if (term.implies(targetTerm, context)) {
                    continue;
                }

                if (guarded) {
                    ConstrainedTerm result = applyRule(term, rules);
                    if (result != null) {
                        if (visited.add(result))
                        nextQueue.add(result);
                        continue;
                    }
                }

                computeRewriteStep(term);
                if (results.isEmpty()) {
                    /* final term */
                    proofResults.add(term);
                }

                for (int i = 0; getTransition(i) != null; ++i) {
                    if (visited.add(getTransition(i))) {
                        nextQueue.add(getTransition(i));
                    }
                }
            }

            /* swap the queues */
            List<ConstrainedTerm> temp;
            temp = queue;
            queue = nextQueue;
            nextQueue = temp;
            nextQueue.clear();
            guarded = true;

            /*
            for (ConstrainedTerm result : queue) {
                System.err.println(result);
            }
            System.err.println("============================================================");
            */
        }

        return proofResults;
    }

}
