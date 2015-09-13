/*
 *  Copyright 2014 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.jso.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.teavm.diagnostics.Diagnostics;
import org.teavm.javascript.spi.GeneratedBy;
import org.teavm.javascript.spi.Sync;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSIndexer;
import org.teavm.jso.JSMethod;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.model.AccessLevel;
import org.teavm.model.AnnotationHolder;
import org.teavm.model.AnnotationReader;
import org.teavm.model.AnnotationValue;
import org.teavm.model.BasicBlock;
import org.teavm.model.CallLocation;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldHolder;
import org.teavm.model.Incoming;
import org.teavm.model.Instruction;
import org.teavm.model.InstructionLocation;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.Phi;
import org.teavm.model.Program;
import org.teavm.model.TryCatchBlock;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.AssignInstruction;
import org.teavm.model.instructions.CastInstruction;
import org.teavm.model.instructions.ClassConstantInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.StringConstantInstruction;
import org.teavm.model.util.InstructionVariableMapper;
import org.teavm.model.util.ModelUtils;
import org.teavm.model.util.ProgramUtils;

/**
 *
 * @author Alexey Andreev
 */
class JavascriptNativeProcessor {
    private ClassReaderSource classSource;
    private Program program;
    private List<Instruction> replacement = new ArrayList<>();
    private NativeJavascriptClassRepository nativeRepos;
    private Diagnostics diagnostics;
    private int methodIndexGenerator;
    private Map<MethodReference, MethodReader> overridenMethodCache = new HashMap<>();

    public JavascriptNativeProcessor(ClassReaderSource classSource) {
        this.classSource = classSource;
        nativeRepos = new NativeJavascriptClassRepository(classSource);
    }

    public ClassReaderSource getClassSource() {
        return classSource;
    }

    public boolean isNative(String className) {
        return nativeRepos.isJavaScriptClass(className);
    }

    public boolean isNativeImplementation(String className) {
        return nativeRepos.isJavaScriptImplementation(className);
    }

    public void setDiagnostics(Diagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public MethodReference isFunctor(String className) {
        if (!nativeRepos.isJavaScriptImplementation(className)) {
            return null;
        }
        ClassReader cls = classSource.get(className);
        if (cls == null) {
            return null;
        }
        Map<MethodDescriptor, MethodReference> methods = new HashMap<>();
        getFunctorMethods(className, methods);
        if (methods.size() == 1) {
            return methods.values().iterator().next();
        }
        return null;
    }

    private void getFunctorMethods(String className, Map<MethodDescriptor, MethodReference> methods) {
        classSource.getAncestors(className).forEach(cls -> {
            if (cls.getAnnotations().get(JSFunctor.class.getName()) != null && isProperFunctor(cls)) {
                MethodReference method = cls.getMethods().iterator().next().getReference();
                if (!methods.containsKey(method.getDescriptor())) {
                    methods.put(method.getDescriptor(), method);
                }
            }
        });
    }

    public void processClass(ClassHolder cls) {
        Set<MethodDescriptor> preservedMethods = new HashSet<>();
        for (String iface : cls.getInterfaces()) {
            if (nativeRepos.isJavaScriptClass(iface)) {
                addPreservedMethods(iface, preservedMethods);
            }
        }
    }

    private void addPreservedMethods(String ifaceName, Set<MethodDescriptor> methods) {
        ClassReader iface = classSource.get(ifaceName);
        for (MethodReader method : iface.getMethods()) {
            methods.add(method.getDescriptor());
        }
        for (String superIfaceName : iface.getInterfaces()) {
            addPreservedMethods(superIfaceName, methods);
        }
    }

    public void processFinalMethods(ClassHolder cls) {
        for (MethodHolder method : cls.getMethods().toArray(new MethodHolder[0])) {
            if (method.hasModifier(ElementModifier.STATIC)) {
                continue;
            }
            if (method.hasModifier(ElementModifier.FINAL) && method.getProgram() != null
                    && method.getProgram().basicBlockCount() > 0) {
                ValueType[] staticSignature = getStaticSignature(method.getReference());
                MethodHolder callerMethod = new MethodHolder(new MethodDescriptor(method.getName() + "$static",
                        staticSignature));
                callerMethod.getModifiers().add(ElementModifier.STATIC);
                final Program program = ProgramUtils.copy(method.getProgram());
                program.createVariable();
                InstructionVariableMapper variableMapper = new InstructionVariableMapper() {
                    @Override protected Variable map(Variable var) {
                        return program.variableAt(var.getIndex() + 1);
                    }
                };
                for (int i = program.variableCount() - 1; i > 0; --i) {
                    program.variableAt(i).getDebugNames().addAll(program.variableAt(i - 1).getDebugNames());
                    program.variableAt(i - 1).getDebugNames().clear();
                }
                for (int i = 0; i < program.basicBlockCount(); ++i) {
                    BasicBlock block = program.basicBlockAt(i);
                    for (Instruction insn : block.getInstructions()) {
                        insn.acceptVisitor(variableMapper);
                    }
                    for (Phi phi : block.getPhis()) {
                        phi.setReceiver(program.variableAt(phi.getReceiver().getIndex() + 1));
                        for (Incoming incoming : phi.getIncomings()) {
                            incoming.setValue(program.variableAt(incoming.getValue().getIndex() + 1));
                        }
                    }
                    for (TryCatchBlock tryCatch : block.getTryCatchBlocks()) {
                        if (tryCatch.getExceptionVariable() != null) {
                            tryCatch.setExceptionVariable(program.variableAt(
                                    tryCatch.getExceptionVariable().getIndex() + 1));
                        }
                    }
                }
                callerMethod.setProgram(program);
                ModelUtils.copyAnnotations(method.getAnnotations(), callerMethod.getAnnotations());
                cls.addMethod(callerMethod);
            }
        }
    }

    private MethodReader getOverridenMethod(MethodReader finalMethod) {
        MethodReference ref = finalMethod.getReference();
        if (!overridenMethodCache.containsKey(ref)) {
            overridenMethodCache.put(ref, findOverridenMethod(finalMethod.getOwnerName(), finalMethod));
        }
        return overridenMethodCache.get(ref);
    }

    private MethodReader findOverridenMethod(String className, MethodReader finalMethod) {
        return classSource.getAncestors(className)
                .skip(1)
                .map(cls -> cls.getMethod(finalMethod.getDescriptor()))
                .filter(method -> method != null)
                .findFirst()
                .orElse(null);
    }

    public void addFunctorField(ClassHolder cls, MethodReference method) {
        if (cls.getAnnotations().get(FunctorImpl.class.getName()) != null) {
            return;
        }

        FieldHolder field = new FieldHolder("$$jso_functor$$");
        field.setLevel(AccessLevel.PUBLIC);
        field.setType(ValueType.parse(JSObject.class));
        cls.addField(field);

        AnnotationHolder annot = new AnnotationHolder(FunctorImpl.class.getName());
        annot.getValues().put("value", new AnnotationValue(method.getDescriptor().toString()));
        cls.getAnnotations().add(annot);
    }

    public void makeSync(ClassHolder cls) {
        Set<MethodDescriptor> methods = new HashSet<>();
        findInheritedMethods(cls, methods, new HashSet<>());
        for (MethodHolder method : cls.getMethods()) {
            if (methods.contains(method.getDescriptor()) && method.getAnnotations().get(Sync.class.getName()) == null) {
                AnnotationHolder annot = new AnnotationHolder(Sync.class.getName());
                method.getAnnotations().add(annot);
            }
        }
    }

    private void findInheritedMethods(ClassReader cls, Set<MethodDescriptor> methods, Set<String> visited) {
        if (!visited.add(cls.getName())) {
            return;
        }
        if (isNative(cls.getName())) {
            for (MethodReader method : cls.getMethods()) {
                if (!method.hasModifier(ElementModifier.STATIC) && !method.hasModifier(ElementModifier.FINAL)
                        && method.getLevel() != AccessLevel.PRIVATE) {
                    methods.add(method.getDescriptor());
                }
            }
        } else if (isNativeImplementation(cls.getName())) {
            if (cls.getParent() != null && !cls.getParent().equals(cls.getName())) {
                ClassReader parentCls = classSource.get(cls.getParent());
                if (parentCls != null) {
                    findInheritedMethods(parentCls, methods, visited);
                }
            }
            for (String iface : cls.getInterfaces()) {
                ClassReader parentCls = classSource.get(iface);
                if (parentCls != null) {
                    findInheritedMethods(parentCls, methods, visited);
                }
            }
        }
    }

    private static ValueType[] getStaticSignature(MethodReference method) {
        ValueType[] signature = method.getSignature();
        ValueType[] staticSignature = new ValueType[signature.length + 1];
        for (int i = 0; i < signature.length; ++i) {
            staticSignature[i + 1] = signature[i];
        }
        staticSignature[0] = ValueType.object(method.getClassName());
        return staticSignature;
    }

    public void processProgram(MethodHolder methodToProcess) {
        program = methodToProcess.getProgram();
        for (int i = 0; i < program.basicBlockCount(); ++i) {
            BasicBlock block = program.basicBlockAt(i);
            List<Instruction> instructions = block.getInstructions();
            for (int j = 0; j < instructions.size(); ++j) {
                Instruction insn = instructions.get(j);
                if (!(insn instanceof InvokeInstruction)) {
                    continue;
                }
                InvokeInstruction invoke = (InvokeInstruction) insn;
                if (!nativeRepos.isJavaScriptClass(invoke.getMethod().getClassName())) {
                    continue;
                }
                replacement.clear();

                MethodReader method = getMethod(invoke.getMethod());
                if (method == null || method.hasModifier(ElementModifier.STATIC)) {
                    continue;
                }

                if (method.hasModifier(ElementModifier.FINAL)) {
                    MethodReader overriden = getOverridenMethod(method);
                    if (overriden != null) {
                        CallLocation callLocation = new CallLocation(methodToProcess.getReference(),
                                insn.getLocation());
                        diagnostics.error(callLocation, "JS final method {{m0}} overrides {{M1}}. "
                                + "Overriding final method of overlay types is prohibited.",
                                method.getReference(), overriden.getReference());
                    }
                    if (method.getProgram() != null && method.getProgram().basicBlockCount() > 0) {
                        invoke.setMethod(new MethodReference(method.getOwnerName(), method.getName() + "$static",
                                getStaticSignature(method.getReference())));
                        invoke.getArguments().add(0, invoke.getInstance());
                        invoke.setInstance(null);
                    }
                    invoke.setType(InvocationType.SPECIAL);
                    continue;
                }

                CallLocation callLocation = new CallLocation(methodToProcess.getReference(), insn.getLocation());
                if (method.getAnnotations().get(JSProperty.class.getName()) != null) {
                    if (isProperGetter(method.getDescriptor())) {
                        String propertyName;
                        AnnotationReader annot = method.getAnnotations().get(JSProperty.class.getName());
                        if (annot.getValue("value") != null) {
                            propertyName = annot.getValue("value").getString();
                        } else {
                            propertyName = method.getName().charAt(0) == 'i' ? cutPrefix(method.getName(), 2)
                                    : cutPrefix(method.getName(), 3);
                        }
                        Variable result = invoke.getReceiver() != null ? program.createVariable() : null;
                        addPropertyGet(propertyName, invoke.getInstance(), result, invoke.getLocation());
                        if (result != null) {
                            result = unwrap(callLocation, result, method.getResultType());
                            copyVar(result, invoke.getReceiver(), invoke.getLocation());
                        }
                    } else if (isProperSetter(method.getDescriptor())) {
                        String propertyName;
                        AnnotationReader annot = method.getAnnotations().get(JSProperty.class.getName());
                        if (annot.getValue("value") != null) {
                            propertyName = annot.getValue("value").getString();
                        } else {
                            propertyName = cutPrefix(method.getName(), 3);
                        }
                        Variable wrapped = wrapArgument(callLocation, invoke.getArguments().get(0),
                                method.parameterType(0));
                        addPropertySet(propertyName, invoke.getInstance(), wrapped, invoke.getLocation());
                    } else {
                        diagnostics.error(callLocation, "Method {{m0}} is not a proper native JavaScript property "
                                + "declaration", invoke.getMethod());
                        continue;
                    }
                } else if (method.getAnnotations().get(JSIndexer.class.getName()) != null) {
                    if (isProperGetIndexer(method.getDescriptor())) {
                        Variable result = invoke.getReceiver() != null ? program.createVariable() : null;
                        addIndexerGet(invoke.getInstance(), wrap(invoke.getArguments().get(0),
                                method.parameterType(0), invoke.getLocation()), result, invoke.getLocation());
                        if (result != null) {
                            result = unwrap(callLocation, result, method.getResultType());
                            copyVar(result, invoke.getReceiver(), invoke.getLocation());
                        }
                    } else if (isProperSetIndexer(method.getDescriptor())) {
                        Variable index = wrap(invoke.getArguments().get(0), method.parameterType(0),
                                invoke.getLocation());
                        Variable value = wrap(invoke.getArguments().get(1), method.parameterType(1),
                                invoke.getLocation());
                        addIndexerSet(invoke.getInstance(), index, value, invoke.getLocation());
                    } else {
                        diagnostics.error(callLocation, "Method {{m0}} is not a proper native JavaScript indexer "
                                + "declaration", invoke.getMethod());
                        continue;
                    }
                } else {
                    String name = method.getName();

                    AnnotationReader methodAnnot = method.getAnnotations().get(JSMethod.class.getName());
                    if (methodAnnot != null) {
                        AnnotationValue redefinedMethodName = methodAnnot.getValue("value");
                        if (redefinedMethodName != null) {
                            name = redefinedMethodName.getString();
                        }
                    }
                    if (method.getResultType() != ValueType.VOID && !isSupportedType(method.getResultType())) {
                        diagnostics.error(callLocation, "Method {{m0}} is not a proper native JavaScript method "
                                + "declaration", invoke.getMethod());
                        continue;
                    }

                    for (ValueType arg : method.getParameterTypes()) {
                        if (!isSupportedType(arg)) {
                            diagnostics.error(callLocation, "Method {{m0}} is not a proper native JavaScript method "
                                    + "or constructor declaration", invoke.getMethod());
                            continue;
                        }
                    }
                    Variable result = invoke.getReceiver() != null ? program.createVariable() : null;
                    InvokeInstruction newInvoke = new InvokeInstruction();
                    ValueType[] signature = new ValueType[method.parameterCount() + 3];
                    Arrays.fill(signature, ValueType.object(JSObject.class.getName()));
                    newInvoke.setMethod(new MethodReference(JS.class.getName(), "invoke", signature));
                    newInvoke.setType(InvocationType.SPECIAL);
                    newInvoke.setReceiver(result);
                    newInvoke.getArguments().add(invoke.getInstance());
                    newInvoke.getArguments().add(addStringWrap(addString(name, invoke.getLocation()),
                            invoke.getLocation()));
                    newInvoke.setLocation(invoke.getLocation());
                    for (int k = 0; k < invoke.getArguments().size(); ++k) {
                        Variable arg = wrapArgument(callLocation, invoke.getArguments().get(k),
                                method.parameterType(k));
                        newInvoke.getArguments().add(arg);
                    }
                    replacement.add(newInvoke);
                    if (result != null) {
                        result = unwrap(callLocation, result, method.getResultType());
                        copyVar(result, invoke.getReceiver(), invoke.getLocation());
                    }
                }
                block.getInstructions().set(j, replacement.get(0));
                block.getInstructions().addAll(j + 1, replacement.subList(1, replacement.size()));
                j += replacement.size() - 1;
            }
        }
    }

    public void processJSBody(ClassHolder cls, MethodHolder methodToProcess) {
        CallLocation location = new CallLocation(methodToProcess.getReference());
        boolean isStatic = methodToProcess.hasModifier(ElementModifier.STATIC);

        // validate parameter names
        AnnotationHolder bodyAnnot = methodToProcess.getAnnotations().get(JSBody.class.getName());
        int jsParamCount = bodyAnnot.getValue("params").getList().size();
        if (methodToProcess.parameterCount() != jsParamCount) {
            diagnostics.error(location, "JSBody method {{m0}} declares " + methodToProcess.parameterCount()
                    + " parameters, but annotation specifies " + jsParamCount, methodToProcess);
            return;
        }

        // remove annotation and make non-native
        methodToProcess.getAnnotations().remove(JSBody.class.getName());
        methodToProcess.getModifiers().remove(ElementModifier.NATIVE);

        // generate parameter types for original method and validate
        int paramCount = methodToProcess.parameterCount();
        if (!isStatic) {
            ++paramCount;
        }
        ValueType[] paramTypes = new ValueType[paramCount];
        int offset = 0;
        if (!isStatic) {
            ValueType paramType = ValueType.object(cls.getName());
            paramTypes[offset++] = paramType;
            if (!isSupportedType(paramType)) {
                diagnostics.error(location, "Non-static JSBody method {{m0}} is owned by non-JS class {{c1}}",
                        methodToProcess.getReference(), cls.getName());
            }
        }
        if (methodToProcess.getResultType() != ValueType.VOID && !isSupportedType(methodToProcess.getResultType())) {
            diagnostics.error(location, "JSBody method {{m0}} returns unsupported type {{t1}}",
                    methodToProcess.getReference(), methodToProcess.getResultType());
        }

        // generate parameter types for proxy method
        for (int i = 0; i < methodToProcess.parameterCount(); ++i) {
            paramTypes[offset++] = methodToProcess.parameterType(i);
        }
        ValueType[] proxyParamTypes = new ValueType[paramCount + 1];
        for (int i = 0; i < paramCount; ++i) {
            proxyParamTypes[i] = ValueType.parse(JSObject.class);
        }
        proxyParamTypes[paramCount] = methodToProcess.getResultType() == ValueType.VOID
                ? ValueType.VOID
                : ValueType.parse(JSObject.class);

        // create proxy method
        MethodHolder proxyMethod = new MethodHolder("$js_body$_" + methodIndexGenerator++, proxyParamTypes);
        proxyMethod.getModifiers().add(ElementModifier.NATIVE);
        proxyMethod.getModifiers().add(ElementModifier.STATIC);
        AnnotationHolder genBodyAnnot = new AnnotationHolder(JSBodyImpl.class.getName());
        genBodyAnnot.getValues().put("script", bodyAnnot.getValue("script"));
        genBodyAnnot.getValues().put("params", bodyAnnot.getValue("params"));
        genBodyAnnot.getValues().put("isStatic", new AnnotationValue(isStatic));
        AnnotationHolder generatorAnnot = new AnnotationHolder(GeneratedBy.class.getName());
        generatorAnnot.getValues().put("value", new AnnotationValue(ValueType.parse(JSBodyGenerator.class)));
        proxyMethod.getAnnotations().add(genBodyAnnot);
        proxyMethod.getAnnotations().add(generatorAnnot);
        cls.addMethod(proxyMethod);

        // create program that invokes proxy method
        program = new Program();
        BasicBlock block = program.createBasicBlock();
        for (int i = 0; i < paramCount; ++i) {
            program.createVariable();
        }
        if (isStatic) {
            program.createVariable();
        }
        methodToProcess.setProgram(program);

        // Generate invoke instruction
        replacement.clear();
        InvokeInstruction invoke = new InvokeInstruction();
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(proxyMethod.getReference());
        for (int i = 0; i < paramCount; ++i) {
            Variable var = program.variableAt(isStatic ? i + 1 : i);
            invoke.getArguments().add(wrapArgument(location, var, paramTypes[i]));
        }
        block.getInstructions().addAll(replacement);
        block.getInstructions().add(invoke);

        // Generate return
        ExitInstruction exit = new ExitInstruction();
        if (methodToProcess.getResultType() != ValueType.VOID) {
            replacement.clear();
            Variable result = program.createVariable();
            invoke.setReceiver(result);
            exit.setValueToReturn(unwrap(location, result, methodToProcess.getResultType()));
            block.getInstructions().addAll(replacement);
        }
        block.getInstructions().add(exit);
    }

    private void addPropertyGet(String propertyName, Variable instance, Variable receiver,
            InstructionLocation location) {
        Variable nameVar = addStringWrap(addString(propertyName, location), location);
        InvokeInstruction insn = new InvokeInstruction();
        insn.setType(InvocationType.SPECIAL);
        insn.setMethod(new MethodReference(JS.class, "get", JSObject.class, JSObject.class, JSObject.class));
        insn.setReceiver(receiver);
        insn.getArguments().add(instance);
        insn.getArguments().add(nameVar);
        insn.setLocation(location);
        replacement.add(insn);
    }

    private void addPropertySet(String propertyName, Variable instance, Variable value, InstructionLocation location) {
        Variable nameVar = addStringWrap(addString(propertyName, location), location);
        InvokeInstruction insn = new InvokeInstruction();
        insn.setType(InvocationType.SPECIAL);
        insn.setMethod(new MethodReference(JS.class, "set", JSObject.class, JSObject.class,
                JSObject.class, void.class));
        insn.getArguments().add(instance);
        insn.getArguments().add(nameVar);
        insn.getArguments().add(value);
        insn.setLocation(location);
        replacement.add(insn);
    }

    private void addIndexerGet(Variable array, Variable index, Variable receiver, InstructionLocation location) {
        InvokeInstruction insn = new InvokeInstruction();
        insn.setType(InvocationType.SPECIAL);
        insn.setMethod(new MethodReference(JS.class, "get", JSObject.class, JSObject.class, JSObject.class));
        insn.setReceiver(receiver);
        insn.getArguments().add(array);
        insn.getArguments().add(index);
        insn.setLocation(location);
        replacement.add(insn);
    }

    private void addIndexerSet(Variable array, Variable index, Variable value, InstructionLocation location) {
        InvokeInstruction insn = new InvokeInstruction();
        insn.setType(InvocationType.SPECIAL);
        insn.setMethod(new MethodReference(JS.class, "set", JSObject.class, JSObject.class,
                JSObject.class, void.class));
        insn.getArguments().add(array);
        insn.getArguments().add(index);
        insn.getArguments().add(value);
        insn.setLocation(location);
        replacement.add(insn);
    }

    private void copyVar(Variable a, Variable b, InstructionLocation location) {
        AssignInstruction insn = new AssignInstruction();
        insn.setAssignee(a);
        insn.setReceiver(b);
        insn.setLocation(location);
        replacement.add(insn);
    }

    private Variable addStringWrap(Variable var, InstructionLocation location) {
        return wrap(var, ValueType.object("java.lang.String"), location);
    }

    private Variable addString(String str, InstructionLocation location) {
        Variable var = program.createVariable();
        StringConstantInstruction nameInsn = new StringConstantInstruction();
        nameInsn.setReceiver(var);
        nameInsn.setConstant(str);
        nameInsn.setLocation(location);
        replacement.add(nameInsn);
        return var;
    }

    private Variable unwrap(CallLocation location, Variable var, ValueType type) {
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                    return unwrap(var, "unwrapBoolean", ValueType.parse(JSObject.class), ValueType.BOOLEAN,
                            location.getSourceLocation());
                case BYTE:
                    return unwrap(var, "unwrapByte", ValueType.parse(JSObject.class), ValueType.BYTE,
                            location.getSourceLocation());
                case SHORT:
                    return unwrap(var, "unwrapShort", ValueType.parse(JSObject.class), ValueType.SHORT,
                            location.getSourceLocation());
                case INTEGER:
                    return unwrap(var, "unwrapInt", ValueType.parse(JSObject.class), ValueType.INTEGER,
                            location.getSourceLocation());
                case CHARACTER:
                    return unwrap(var, "unwrapCharacter", ValueType.parse(JSObject.class), ValueType.CHARACTER,
                            location.getSourceLocation());
                case DOUBLE:
                    return unwrap(var, "unwrapDouble", ValueType.parse(JSObject.class), ValueType.DOUBLE,
                            location.getSourceLocation());
                case FLOAT:
                    return unwrap(var, "unwrapFloat", ValueType.parse(JSObject.class), ValueType.FLOAT,
                            location.getSourceLocation());
                case LONG:
                    break;
            }
        } else if (type instanceof ValueType.Object) {
            String className = ((ValueType.Object) type).getClassName();
            if (className.equals(JSObject.class.getName())) {
                return var;
            } else if (className.equals("java.lang.String")) {
                return unwrap(var, "unwrapString", ValueType.parse(JSObject.class), ValueType.parse(String.class),
                        location.getSourceLocation());
            } else if (isNative(className)) {
                Variable result = program.createVariable();
                CastInstruction castInsn = new CastInstruction();
                castInsn.setReceiver(result);
                castInsn.setValue(var);
                castInsn.setTargetType(type);
                castInsn.setLocation(location.getSourceLocation());
                replacement.add(castInsn);
                return result;
            }
        } else if (type instanceof ValueType.Array) {
            return unwrapArray(location, var, (ValueType.Array) type);
        }
        diagnostics.error(location, "Unsupported type: {{t0}}", type);
        return var;
    }

    private Variable unwrapArray(CallLocation location, Variable var, ValueType.Array type) {
        ValueType itemType = type;
        int degree = 0;
        while (itemType instanceof ValueType.Array) {
            ++degree;
            itemType = ((ValueType.Array) itemType).getItemType();
        }

        CastInstruction castInsn = new CastInstruction();
        castInsn.setValue(var);
        castInsn.setTargetType(ValueType.parse(JSArrayReader.class));
        var = program.createVariable();
        castInsn.setReceiver(var);
        castInsn.setLocation(location.getSourceLocation());
        replacement.add(castInsn);

        var = degree == 1
                ? unwrapSingleDimensionArray(location, var, itemType)
                : unwrapMultiDimensionArray(location, var, itemType, degree);

        return var;
    }

    private Variable unwrapSingleDimensionArray(CallLocation location, Variable var, ValueType type) {
        Variable result = program.createVariable();

        InvokeInstruction insn = new InvokeInstruction();
        insn.setMethod(singleDimensionArrayUnwrapper(type));
        insn.setType(InvocationType.SPECIAL);

        if (insn.getMethod().parameterCount() == 2) {
            Variable cls = program.createVariable();
            ClassConstantInstruction clsInsn = new ClassConstantInstruction();
            clsInsn.setConstant(type);
            clsInsn.setLocation(location.getSourceLocation());
            clsInsn.setReceiver(cls);
            replacement.add(clsInsn);
            insn.getArguments().add(cls);
        }

        insn.getArguments().add(var);
        insn.setReceiver(result);
        replacement.add(insn);
        return result;
    }

    private Variable unwrapMultiDimensionArray(CallLocation location, Variable var, ValueType type, int degree) {
        Variable function = program.createVariable();

        InvokeInstruction insn = new InvokeInstruction();
        insn.setMethod(multipleDimensionArrayUnwrapper(type));
        insn.setType(InvocationType.SPECIAL);

        if (insn.getMethod().parameterCount() == 1) {
            Variable cls = program.createVariable();
            ClassConstantInstruction clsInsn = new ClassConstantInstruction();
            clsInsn.setConstant(type);
            clsInsn.setLocation(location.getSourceLocation());
            clsInsn.setReceiver(cls);
            replacement.add(clsInsn);
            insn.getArguments().add(cls);
        }

        insn.setReceiver(function);
        replacement.add(insn);

        while (--degree > 1) {
            type = ValueType.arrayOf(type);
            Variable cls = program.createVariable();

            ClassConstantInstruction clsInsn = new ClassConstantInstruction();
            clsInsn.setConstant(type);
            clsInsn.setLocation(location.getSourceLocation());
            clsInsn.setReceiver(cls);
            replacement.add(clsInsn);

            insn = new InvokeInstruction();
            insn.setMethod(new MethodReference(JS.class, "arrayUnmapper", Class.class, Function.class,
                    Function.class));
            insn.setType(InvocationType.SPECIAL);
            insn.getArguments().add(cls);
            insn.getArguments().add(function);
            function = program.createVariable();
            insn.setReceiver(function);
            replacement.add(insn);
        }

        Variable cls = program.createVariable();
        ClassConstantInstruction clsInsn = new ClassConstantInstruction();
        clsInsn.setConstant(ValueType.arrayOf(type));
        clsInsn.setLocation(location.getSourceLocation());
        clsInsn.setReceiver(cls);
        replacement.add(clsInsn);

        insn = new InvokeInstruction();
        insn.setMethod(new MethodReference(JS.class, "unmapArray", Class.class, JSArrayReader.class, Function.class,
                Object[].class));
        insn.getArguments().add(cls);
        insn.getArguments().add(var);
        insn.getArguments().add(function);
        insn.setReceiver(var);
        insn.setType(InvocationType.SPECIAL);
        insn.setLocation(location.getSourceLocation());
        replacement.add(insn);

        return var;
    }

    private MethodReference singleDimensionArrayUnwrapper(ValueType itemType) {
        if (itemType instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) itemType).getKind()) {
                case BOOLEAN:
                    return new MethodReference(JS.class, "unwrapBooleanArray", JSArrayReader.class, boolean[].class);
                case BYTE:
                    return new MethodReference(JS.class, "unwrapByteArray", JSArrayReader.class, byte[].class);
                case SHORT:
                    return new MethodReference(JS.class, "unwrapShortArray", JSArrayReader.class, short[].class);
                case CHARACTER:
                    return new MethodReference(JS.class, "unwrapCharArray", JSArrayReader.class, char[].class);
                case INTEGER:
                    return new MethodReference(JS.class, "unwrapIntArray", JSArrayReader.class, int[].class);
                case FLOAT:
                    return new MethodReference(JS.class, "unwrapFloatArray", JSArrayReader.class, float[].class);
                case DOUBLE:
                    return new MethodReference(JS.class, "unwrapDoubleArray", JSArrayReader.class, double[].class);
                default:
                    break;
            }
        } else if (itemType.isObject(String.class)) {
            return new MethodReference(JS.class, "unwrapStringArray", JSArrayReader.class, String[].class);
        }
        return new MethodReference(JS.class, "unwrapArray", Class.class, JSArrayReader.class, JSObject[].class);
    }

    private MethodReference multipleDimensionArrayUnwrapper(ValueType itemType) {
        if (itemType instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) itemType).getKind()) {
                case BOOLEAN:
                    return new MethodReference(JS.class, "booleanArrayUnwrapper", Function.class);
                case BYTE:
                    return new MethodReference(JS.class, "byteArrayUnwrapper", Function.class);
                case SHORT:
                    return new MethodReference(JS.class, "shortArrayUnwrapper", Function.class);
                case CHARACTER:
                    return new MethodReference(JS.class, "charArrayUnwrapper", Function.class);
                case INTEGER:
                    return new MethodReference(JS.class, "intArrayUnwrapper", Function.class);
                case FLOAT:
                    return new MethodReference(JS.class, "floatArrayUnwrapper", Function.class);
                case DOUBLE:
                    return new MethodReference(JS.class, "doubleArrayUnwrapper", Function.class);
                default:
                    break;
            }
        } else if (itemType.isObject(String.class)) {
            return new MethodReference(JS.class, "stringArrayUnwrapper", Function.class);
        }
        return new MethodReference(JS.class, "arrayUnwrapper", Class.class, Function.class);
    }

    private Variable unwrap(Variable var, String methodName, ValueType argType, ValueType resultType,
            InstructionLocation location) {
        if (!argType.isObject(JSObject.class.getName())) {
            Variable castValue = program.createVariable();
            CastInstruction castInsn = new CastInstruction();
            castInsn.setValue(var);
            castInsn.setReceiver(castValue);
            castInsn.setLocation(location);
            castInsn.setTargetType(argType);
            replacement.add(castInsn);
            var = castValue;
        }
        Variable result = program.createVariable();
        InvokeInstruction insn = new InvokeInstruction();
        insn.setMethod(new MethodReference(JS.class.getName(), methodName, argType, resultType));
        insn.getArguments().add(var);
        insn.setReceiver(result);
        insn.setType(InvocationType.SPECIAL);
        insn.setLocation(location);
        replacement.add(insn);
        return result;
    }

    private Variable wrapArgument(CallLocation location, Variable var, ValueType type) {
        if (type instanceof ValueType.Object) {
            String className = ((ValueType.Object) type).getClassName();
            ClassReader cls = classSource.get(className);
            if (cls.getAnnotations().get(JSFunctor.class.getName()) != null) {
                return wrapFunctor(location, var, cls);
            }
        }
        return wrap(var, type, location.getSourceLocation());
    }

    private boolean isProperFunctor(ClassReader type) {
        return type.hasModifier(ElementModifier.INTERFACE) && type.getMethods().size() == 1;
    }

    private Variable wrapFunctor(CallLocation location, Variable var, ClassReader type) {
        if (!isProperFunctor(type)) {
            diagnostics.error(location, "Wrong functor: {{c0}}", type.getName());
            return var;
        }
        String name = type.getMethods().iterator().next().getName();
        Variable functor = program.createVariable();
        Variable nameVar = addStringWrap(addString(name, location.getSourceLocation()), location.getSourceLocation());
        InvokeInstruction insn = new InvokeInstruction();
        insn.setType(InvocationType.SPECIAL);
        insn.setMethod(new MethodReference(JS.class, "function", JSObject.class, JSObject.class, JSObject.class));
        insn.setReceiver(functor);
        insn.getArguments().add(var);
        insn.getArguments().add(nameVar);
        insn.setLocation(location.getSourceLocation());
        replacement.add(insn);
        return functor;
    }

    private Variable wrap(Variable var, ValueType type, InstructionLocation location) {
        if (type instanceof ValueType.Object) {
            String className = ((ValueType.Object) type).getClassName();
            if (!className.equals("java.lang.String")) {
                return var;
            }
        }
        Variable result = program.createVariable();

        ValueType itemType = type;
        int degree = 0;
        while (itemType instanceof ValueType.Array) {
            itemType = ((ValueType.Array) itemType).getItemType();
            ++degree;
        }

        if (degree <= 1) {
            InvokeInstruction insn = new InvokeInstruction();
            insn.setMethod(new MethodReference(JS.class.getName(), "wrap", getWrappedType(type),
                    getWrapperType(type)));
            insn.getArguments().add(var);
            insn.setReceiver(result);
            insn.setType(InvocationType.SPECIAL);
            insn.setLocation(location);
            replacement.add(insn);
        } else {
            Variable function = program.createVariable();

            InvokeInstruction insn = new InvokeInstruction();
            insn.setMethod(getWrapperFunction(itemType));
            insn.setReceiver(function);
            insn.setType(InvocationType.SPECIAL);
            insn.setLocation(location);
            replacement.add(insn);

            while (--degree > 1) {
                insn = new InvokeInstruction();
                insn.setMethod(new MethodReference(JS.class, "arrayMapper", Function.class, Function.class));
                insn.getArguments().add(function);
                function = program.createVariable();
                insn.setReceiver(function);
                insn.setType(InvocationType.SPECIAL);
                insn.setLocation(location);
                replacement.add(insn);
            }

            insn = new InvokeInstruction();
            insn.setMethod(new MethodReference(JS.class.getName(), "map", getWrappedType(type),
                    ValueType.parse(Function.class), getWrapperType(type)));
            insn.getArguments().add(var);
            insn.getArguments().add(function);
            insn.setReceiver(result);
            insn.setType(InvocationType.SPECIAL);
            insn.setLocation(location);
            replacement.add(insn);
        }
        return result;
    }

    private MethodReference getWrapperFunction(ValueType type) {
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                    return new MethodReference(JS.class, "booleanArrayWrapper", Function.class);
                case BYTE:
                    return new MethodReference(JS.class, "byteArrayWrapper", Function.class);
                case SHORT:
                    return new MethodReference(JS.class, "shortArrayWrapper", Function.class);
                case CHARACTER:
                    return new MethodReference(JS.class, "charArrayWrapper", Function.class);
                case INTEGER:
                    return new MethodReference(JS.class, "intArrayWrapper", Function.class);
                case FLOAT:
                    return new MethodReference(JS.class, "floatArrayWrapper", Function.class);
                case DOUBLE:
                    return new MethodReference(JS.class, "doubleArrayWrapper", Function.class);
                default:
                    break;
            }
        } else if (type.isObject(String.class)) {
            return new MethodReference(JS.class, "stringArrayWrapper", Function.class);
        }
        return new MethodReference(JS.class, "arrayWrapper", Function.class);
    }

    private ValueType getWrappedType(ValueType type) {
        if (type instanceof ValueType.Array) {
            ValueType itemType = ((ValueType.Array) type).getItemType();
            if (itemType instanceof ValueType.Array) {
                return ValueType.parse(Object[].class);
            } else {
                return ValueType.arrayOf(getWrappedType(itemType));
            }
        } else if (type instanceof ValueType.Object) {
            if (type.isObject(String.class)) {
                return type;
            } else {
                return ValueType.parse(JSObject.class);
            }
        } else {
            return type;
        }
    }

    private ValueType getWrapperType(ValueType type) {
        if (type instanceof ValueType.Array) {
            return ValueType.parse(JSArray.class);
        } else {
            return ValueType.parse(JSObject.class);
        }
    }

    private MethodReader getMethod(MethodReference ref) {
        ClassReader cls = classSource.get(ref.getClassName());
        MethodReader method = cls.getMethod(ref.getDescriptor());
        if (method != null) {
            return method;
        }
        for (String iface : cls.getInterfaces()) {
            method = getMethod(new MethodReference(iface, ref.getDescriptor()));
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private boolean isProperGetter(MethodDescriptor desc) {
        if (desc.parameterCount() > 0 || !isSupportedType(desc.getResultType())) {
            return false;
        }
        if (desc.getResultType().equals(ValueType.BOOLEAN)) {
            if (isProperPrefix(desc.getName(), "is")) {
                return true;
            }
        }
        return isProperPrefix(desc.getName(), "get");
    }

    private boolean isProperSetter(MethodDescriptor desc) {
        if (desc.parameterCount() != 1 || !isSupportedType(desc.parameterType(0))
                || desc.getResultType() != ValueType.VOID) {
            return false;
        }
        return isProperPrefix(desc.getName(), "set");
    }

    private boolean isProperPrefix(String name, String prefix) {
        if (!name.startsWith(prefix) || name.length() == prefix.length()) {
            return false;
        }
        char c = name.charAt(prefix.length());
        return Character.isUpperCase(c);
    }

    private boolean isProperGetIndexer(MethodDescriptor desc) {
        return desc.parameterCount() == 1 && isSupportedType(desc.parameterType(0))
                && isSupportedType(desc.getResultType());
    }

    private boolean isProperSetIndexer(MethodDescriptor desc) {
        return desc.parameterCount() == 2 && isSupportedType(desc.parameterType(0))
                && isSupportedType(desc.parameterType(0)) && desc.getResultType() == ValueType.VOID;
    }

    private String cutPrefix(String name, int prefixLength) {
        if (name.length() == prefixLength + 1) {
            return name.substring(prefixLength).toLowerCase();
        }
        char c = name.charAt(prefixLength + 1);
        if (Character.isUpperCase(c)) {
            return name.substring(prefixLength);
        }
        return Character.toLowerCase(name.charAt(prefixLength)) + name.substring(prefixLength + 1);
    }

    private boolean isSupportedType(ValueType type) {
        if (type == ValueType.VOID) {
            return false;
        }
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case LONG:
                    return false;
                default:
                    return true;
            }
        } else if (type instanceof ValueType.Array) {
            return isSupportedType(((ValueType.Array) type).getItemType());
        } else if (type instanceof ValueType.Object) {
            String typeName = ((ValueType.Object) type).getClassName();
            return typeName.equals("java.lang.String") || nativeRepos.isJavaScriptClass(typeName);
        } else {
            return false;
        }
    }
}