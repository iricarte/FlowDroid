package soot.jimple.infoflow.river;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import heros.solver.Pair;
import soot.*;
import soot.jimple.Stmt;
import soot.jimple.infoflow.results.*;
import soot.jimple.infoflow.sourcesSinks.definitions.SourceSinkCondition;
import soot.util.MultiMap;

/**
 * A condition that checks additional data flow to see whether a source or sink
 * is valid or not
 *
 * @author Steven Arzt
 *
 */
public class AdditionalFlowCondition extends SourceSinkCondition {

    private final Set<String> classNamesOnPath;
    private final Set<String> signaturesOnPath;
    private final Set<String> excludedClassNames;

    private Set<SootMethod> methodsOnPath = null;
    private Set<SootClass> classesOnPath = null;
    private Set<SootClass> excludedClasses = null;

    /**
     * Create a new additional flow condition
     *
     * @param classNamesOnPath class names that have to be on the path
     * @param signaturesOnPath signatures that have to be on the path
     * @param excludedClassNames class names of primary sinks that should be filtered without context,
     *                           e.g. ByteArrayOutputStream for OutputStream
     */
    public AdditionalFlowCondition(Set<String> classNamesOnPath, Set<String> signaturesOnPath,
                                   Set<String> excludedClassNames) {
        this.classNamesOnPath = classNamesOnPath;
        this.signaturesOnPath = signaturesOnPath;
        this.excludedClassNames = excludedClassNames;
    }

    @Override
    public boolean evaluate(DataFlowResult result, InfoflowResults results) {
        // If we have nothing to check, we accept everything
        if (isEmpty())
            return true;

        MultiMap<ResultSourceInfo, ResultSinkInfo> additionalResults = results.getAdditionalResults();
        if (additionalResults == null || additionalResults.isEmpty())
            return false;

        // Get the connected flows
        final Stmt sinkStmt = result.getSink().getStmt();
        final Type baseType = result.getSource().getAccessPath().getBaseType();
        // Shortcut: There won't be a path if the class is excluded anyway
        if (this.classMatches(baseType.toString(), excludedClassNames))
            return false;

        // Because we injected the taint in the SecondaryFlowGenerator with a SecondarySinkDefinition,
        // if there is a flow containing the sink, it is always also in the MultiMap.
        Pair<Set<String>, Set<String>> flows = getSignaturesAndClassNamesReachedFromSink(additionalResults, sinkStmt);
        boolean sigMatch = signaturesOnPath == null || signaturesOnPath.isEmpty()
                || flows.getO1().stream().anyMatch(this::signatureMatches);
        boolean classMatch = classesOnPath == null || classesOnPath.isEmpty()
                || flows.getO2().stream().anyMatch(c -> this.classMatches(c, classNamesOnPath));

        return sigMatch && classMatch;
    }

    private boolean signatureMatches(String sig) {
        if (signaturesOnPath.contains(sig))
            return true;

        SootMethod sm = Scene.v().grabMethod(sig);
        if (sm == null)
            return false;

        for (SootClass ifc : sm.getDeclaringClass().getInterfaces()) {
            SootMethod superMethod = ifc.getMethodUnsafe(sm.getSubSignature());
            if (superMethod != null && signaturesOnPath.contains(superMethod.getSignature()))
                return true;
        }

        SootClass superClass = sm.getDeclaringClass().getSuperclassUnsafe();
        while (superClass != null) {
            SootMethod superMethod = superClass.getMethodUnsafe(sm.getSubSignature());
            if (superMethod != null && signaturesOnPath.contains(superMethod.getSignature()))
                return true;
            superClass = superClass.getSuperclassUnsafe();
        }

        return false;
    }


    private boolean classMatches(String sig, Set<String> classes) {
        if (classes.contains(sig))
            return true;

        SootClass sc = Scene.v().getSootClassUnsafe(sig);
        if (sc == null)
            return false;

        for (SootClass ifc : sc.getInterfaces())
            if (classes.contains(ifc.getName()))
                return true;

        SootClass superClass = sc.getSuperclassUnsafe();
        while (superClass != null) {
            if (classes.contains(superClass.getName()))
                return true;
            superClass = superClass.getSuperclassUnsafe();
        }

        return false;
    }

    @Override
    public Set<SootMethod> getReferencedMethods() {
        if (methodsOnPath == null)
            methodsOnPath = ensureSootMethodsOnPath(signaturesOnPath);
        return methodsOnPath;
    }

    @Override
    public Set<SootClass> getReferencedClasses() {
        if (classesOnPath == null)
            classesOnPath = ensureSootClassesOnPath(classNamesOnPath);
        return classesOnPath;
    }

    @Override
    public Set<SootClass> getExcludedClasses() {
        if (excludedClasses == null)
            excludedClasses = ensureSootClassesOnPath(excludedClassNames);
        return excludedClasses;
    }

    /**
     * Return the signatures that should be on the path
     *
     * @return unmodifiable set of signatures
     */
    public Set<String> getSignaturesOnPath() {
        return Collections.unmodifiableSet(signaturesOnPath);
    }

    /**
     * Return the class names that should be on the path
     *
     * @return unmodifiable set of class names
     */
    public Set<String> getClassNamesOnPath() {
        return Collections.unmodifiableSet(classNamesOnPath);
    }

    /**
     * Return the excluded class names
     *
     * @return unmodifiable set of excluded class names
     */
    public Set<String> getExcludedClassNames() {
        return Collections.unmodifiableSet(excludedClassNames);
    }

    /**
     * Ensures that the set of Soot methods on the data flow path has been
     * initialized
     *
     * @param signatureSet signatures to resolve
     */
    private Set<SootMethod> ensureSootMethodsOnPath(Set<String> signatureSet) {
        Set<SootMethod> resolved = new HashSet<>();
        if (signatureSet != null && !signatureSet.isEmpty()) {
            for (String sig : signatureSet) {
                SootMethod sm = Scene.v().grabMethod(sig);
                if (sm != null)
                    resolved.add(sm);
            }
        }
        return resolved;
    }

    /**
     * Ensures that the set of Soot classeson the data flow path has been
     * initialized
     */
    private Set<SootClass> ensureSootClassesOnPath(Set<String> classNameSet) {
        Set<SootClass> resolved = new HashSet<>();
        if (classNameSet != null && !classNameSet.isEmpty()) {
            for (String className : classNameSet) {
                SootClass sc = Scene.v().getSootClassUnsafe(className);
                if (sc != null)
                    resolved.add(sc);
            }
        }
        return resolved;
    }

    /**
     * Retrieves the signatures and classes that can be reached from the primary sink/secondary source
     *
     * @param additionalResults MultiMap containing the additional results
     * @param primarySinkStmt Sink of interest
     * @return A list of all callee signatures and a list of declaring classes on the path from the sink on
     */
    private Pair<Set<String>, Set<String>>
    getSignaturesAndClassNamesReachedFromSink(MultiMap<ResultSourceInfo, ResultSinkInfo> additionalResults,
                                              Stmt primarySinkStmt) {
        Set<String> sigSet = new HashSet<>();
        Set<String> classSet = new HashSet<>();

        for (ResultSourceInfo secondarySourceInfo : additionalResults.keySet()) {
            // Match secondary source with primary sink of interest
            if (secondarySourceInfo.getStmt() == primarySinkStmt
                    && secondarySourceInfo.getDefinition() instanceof ConditionalSecondarySourceDefinition) {
                for (ResultSinkInfo secondarySinkInfo : additionalResults.get(secondarySourceInfo)) {
                    if (secondarySourceInfo.getPath() == null) {
                        // Fall back if path reconstruction is not enabled
                        SootMethod callee = secondarySinkInfo.getStmt().getInvokeExpr().getMethod();
                        sigSet.add(callee.getSignature());
                        classSet.add(callee.getDeclaringClass().getName());
                    } else {
                        Stmt[] path = secondarySourceInfo.getPath();
                        for (Stmt stmt : path) {
                            if (stmt.containsInvokeExpr()) {
                                // Register all calls on the path
                                SootMethod callee = stmt.getInvokeExpr().getMethod();
                                sigSet.add(callee.getSignature());
                                classSet.add(callee.getDeclaringClass().getName());
                            }
                        }
                    }
                }
            }
        }
        return new Pair<>(sigSet, classSet);
    }

    /**
     * Gets whether this flow condition is empty, i.e., has nothing to check for
     *
     * @return True if this flow condition is empty, false otherwise
     */
    public boolean isEmpty() {
        return (classNamesOnPath == null || classNamesOnPath.isEmpty())
                && (signaturesOnPath == null || signaturesOnPath.isEmpty());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((classNamesOnPath == null) ? 0 : classNamesOnPath.hashCode());
        result = prime * result + ((signaturesOnPath == null) ? 0 : signaturesOnPath.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AdditionalFlowCondition other = (AdditionalFlowCondition) obj;
        if (classNamesOnPath == null) {
            if (other.classNamesOnPath != null)
                return false;
        } else if (!classNamesOnPath.equals(other.classNamesOnPath))
            return false;
        if (signaturesOnPath == null) {
            if (other.signaturesOnPath != null)
                return false;
        } else if (!signaturesOnPath.equals(other.signaturesOnPath))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "AdditionalFlowCondition: " +
                "classNamesOnPath=" + classNamesOnPath +
                ", signaturesOnPath=" + signaturesOnPath;
    }
}
