/*
 * Copyright 2024 Apartium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.apartium.cocoabeans.commands;

import net.apartium.cocoabeans.CollectionHelpers;
import net.apartium.cocoabeans.commands.exception.ExceptionHandle;
import net.apartium.cocoabeans.commands.exception.HandleExceptionVariant;
import net.apartium.cocoabeans.commands.parsers.*;
import net.apartium.cocoabeans.commands.parsers.factory.ParserFactory;
import net.apartium.cocoabeans.commands.parsers.factory.WithParserFactory;
import net.apartium.cocoabeans.commands.requirements.*;
import net.apartium.cocoabeans.reflect.ClassUtils;
import net.apartium.cocoabeans.reflect.MethodUtils;
import net.apartium.cocoabeans.structs.Entry;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/*package-private*/ class RegisteredCommand {

    private static final Comparator<HandleExceptionVariant> HANDLE_EXCEPTION_VARIANT_COMPARATOR = (a, b) -> Integer.compare(b.priority(), a.priority());

    private static final Comparator<RegisteredCommandVariant> REGISTERED_COMMAND_VARIANT_COMPARATOR = (a, b) -> Integer.compare(b.priority(), a.priority());

    public record RegisteredCommandNode(CommandNode listener, RequirementSet requirements) {}

    private final CommandManager commandManager;

    private final List<RegisteredCommandNode> commands = new ArrayList<>();
    private final List<HandleExceptionVariant> handleExceptionVariants = new ArrayList<>();
    private final CommandBranchProcessor commandBranchProcessor;


    RegisteredCommand(CommandManager commandManager) {
        this.commandManager = commandManager;

        commandBranchProcessor = new CommandBranchProcessor(commandManager);
    }

    public void addNode(CommandNode node) {
        Class<?> clazz = node.getClass();
        RequirementSet requirementSet = new RequirementSet(findAllRequirements(node, clazz));

        Method fallbackHandle;
        try {
            fallbackHandle = clazz.getMethod("fallbackHandle", Sender.class, String.class, String[].class);
        } catch (Exception e) {
            throw new RuntimeException("What is going on here", e);
        }

        this.commands.add(new RegisteredCommandNode(
                node,
                new RequirementSet(
                        requirementSet,
                        createRequirementSet(node, fallbackHandle.getAnnotations())
                ))
        );
        Map<String, ArgumentParser<?>> argumentTypeHandlerMap = new HashMap<>();
        MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();

        // Add class parsers & source parser
        for (Class<?> c : ClassUtils.getSuperClassAndInterfaces(clazz)) {
            for (var entry : serializeArgumentTypeHandler(node, c.getAnnotations()).entrySet()) {
                argumentTypeHandlerMap.putIfAbsent(entry.getKey(), entry.getValue());
            }
            
            for (Method method : clazz.getMethods()) {
                try {
                    addSourceParser(
                            node,
                            argumentTypeHandlerMap,
                            publicLookup,
                            method,
                            c.getMethod(method.getName(), method.getParameterTypes())
                    );
                } catch (NoSuchMethodException e) {
                    continue;
                }
            }
        }

        for (var entry : commandManager.argumentTypeHandlerMap.entrySet()) {
            argumentTypeHandlerMap.putIfAbsent(entry.getKey(), entry.getValue());
        }


        CommandOption commandOption = createCommandOption(requirementSet, commandBranchProcessor);

        for (Method method : clazz.getMethods()) {
            SubCommand[] subCommands = method.getAnnotationsByType(SubCommand.class);

            for (SubCommand subCommand : subCommands) {
                parseSubCommand(method, subCommand, clazz, argumentTypeHandlerMap, requirementSet, publicLookup, node, commandOption);
            }

            ExceptionHandle exceptionHandle = method.getAnnotation(ExceptionHandle.class);
            if (exceptionHandle != null) {
                try {
                    CollectionHelpers.addElementSorted(
                            handleExceptionVariants,
                            new HandleExceptionVariant(
                                publicLookup.unreflect(method),
                                Arrays.stream(method.getParameters()).map(Parameter::getType).toArray(Class[]::new),
                                node,
                                exceptionHandle.priority()
                            ),
                            HANDLE_EXCEPTION_VARIANT_COMPARATOR
                    );
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }


            for (Method targetMethod : MethodUtils.getMethodsFromSuperClassAndInterface(method)) {
                handleSubCommand(node, clazz, requirementSet, argumentTypeHandlerMap, publicLookup, commandOption, method, targetMethod);
            }

        }

    }

    private void handleSubCommand(CommandNode node, Class<?> clazz, RequirementSet requirementSet, Map<String, ArgumentParser<?>> argumentTypeHandlerMap, MethodHandles.Lookup publicLookup, CommandOption commandOption, Method method, Method targetMethod) {
        ExceptionHandle exceptionHandle;
        if (targetMethod == null)
            return;



        SubCommand[] superSubCommands = targetMethod.getAnnotationsByType(SubCommand.class);

        for (SubCommand subCommand : superSubCommands) {
            parseSubCommand(method, subCommand, clazz, argumentTypeHandlerMap, requirementSet, publicLookup, node, commandOption);
        }

        exceptionHandle = targetMethod.getAnnotation(ExceptionHandle.class);
        if (exceptionHandle != null) {
            try {
                CollectionHelpers.addElementSorted(
                        handleExceptionVariants,
                        new HandleExceptionVariant(
                                publicLookup.unreflect(method),
                                Arrays.stream(method.getParameters()).map(Parameter::getType).toArray(Class[]::new),
                                node,
                                exceptionHandle.priority()
                        ),
                        HANDLE_EXCEPTION_VARIANT_COMPARATOR
                );
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }


    private void addSourceParser(CommandNode node, Map<String, ArgumentParser<?>> argumentTypeHandlerMap, MethodHandles.Lookup publicLookup, Method method, Method targetMethod) {
        SourceParser sourceParser = targetMethod.getAnnotation(SourceParser.class);
        if (sourceParser == null)
            return;

        if (!method.getReturnType().equals(Map.class))
            throw new RuntimeException("Wrong return type: " + method.getReturnType());

        try {
            argumentTypeHandlerMap.putIfAbsent(sourceParser.keyword(), new SourceParserImpl<>(
                    node,
                    sourceParser.keyword(),
                    sourceParser.clazz(),
                    sourceParser.priority(),
                    publicLookup.unreflect(method),
                    sourceParser.resultMaxAgeInMills()
            ));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void parseSubCommand(Method method, SubCommand subCommand, Class<?> clazz, Map<String, ArgumentParser<?>> argumentTypeHandlerMap, RequirementSet requirementSet, MethodHandles.Lookup publicLookup, CommandNode node, CommandOption commandOption) {
        if (subCommand == null)
            return;

        if (!Modifier.isPublic(method.getModifiers()))
            return;

        // TODO replace to warning?
        if (Modifier.isStatic(method.getModifiers()))
            throw new RuntimeException("Static method " + clazz.getName() + "#" + method.getName() + " is not supported");


        Map<String, ArgumentParser<?>> methodArgumentTypeHandlerMap = new HashMap<>(serializeArgumentTypeHandler(node, method.getAnnotations()));

        for (Method targetMethod : MethodUtils.getMethodsFromSuperClassAndInterface(method)) {
            Map<String, ArgumentParser<?>> withParserMap = serializeArgumentTypeHandler(node, targetMethod.getAnnotations());
            for (var entry : withParserMap.entrySet()) {
                if (methodArgumentTypeHandlerMap.containsKey(entry.getKey()))
                    continue;

                methodArgumentTypeHandlerMap.put(entry.getKey(), entry.getValue());
            }
        }

        for (var entry : argumentTypeHandlerMap.entrySet()) {
            if (methodArgumentTypeHandlerMap.containsKey(entry.getKey()))
                continue;

            methodArgumentTypeHandlerMap.put(entry.getKey(), entry.getValue());
        }




        RequirementSet methodRequirements = new RequirementSet(
                findAllRequirements(node, method),
                requirementSet
        );

        String[] split = subCommand.value().split("\\s+");
        if (split.length == 0 || split.length == 1 && split[0].isEmpty()) {
            CommandOption cmdOption = createCommandOption(methodRequirements, commandBranchProcessor);

            try {
                CollectionHelpers.addElementSorted(
                        cmdOption.getRegisteredCommandVariants(),
                        new RegisteredCommandVariant(
                            publicLookup.unreflect(method),
                            serializeParameters(node, method.getParameters()),
                            node,
                            subCommand.priority()
                        ),
                        REGISTERED_COMMAND_VARIANT_COMPARATOR
                );
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Error accessing method", e);
            }

            return;
        }

        CommandOption currentCommandOption = commandOption;
        for (int index = 0; index < split.length; index++) {
            String cmd = split[index];

            //  TODO may need to split requirements so it will be faster and joined stuff
            RequirementSet requirements = index == 0 ? methodRequirements : new RequirementSet();

            if (cmd.startsWith("<") && cmd.endsWith(">")) {
                // TODO may need to check that can be parser before doing all calculation

                boolean isOptional = cmd.startsWith("<?") || cmd.startsWith("<!?");
                boolean isInvalid = cmd.startsWith("<!") || cmd.startsWith("<?!");

                ArgumentParser<?> typeParser = methodArgumentTypeHandlerMap.get(cmd.substring(1 + (isInvalid ? 1 : 0) + (isOptional ? 1 : 0), cmd.length() - 1));

                if (typeParser == null)
                    throw new RuntimeException("Couldn't resolve " + clazz.getName() + "#" + method.getName() + " parser: " + cmd.substring(1, cmd.length() - 1));


                RegisterArgumentParser<?> finalTypeParser = new RegisterArgumentParser<> (
                        typeParser,
                        isInvalid,
                        isOptional
                );

                CommandBranchProcessor commandBranchProcessor = currentCommandOption.getArgumentTypeHandlerMap().stream()
                        .filter(entry -> entry.key().equals(finalTypeParser))
                        .findAny()
                        .map(Entry::value)
                        .orElse(null);

                if (commandBranchProcessor == null) {
                    commandBranchProcessor = new CommandBranchProcessor(commandManager);
                    CollectionHelpers.addElementSorted(
                            currentCommandOption.getArgumentTypeHandlerMap(),
                            new Entry<>(
                                    finalTypeParser,
                                    commandBranchProcessor
                            ),
                            (a, b) -> b.key().compareTo(a.key())
                    );
                }

                if (finalTypeParser.isOptional()) {
                    CommandBranchProcessor branchProcessor = currentCommandOption.getOptionalArgumentTypeHandlerMap().stream()
                            .filter(entry -> entry.key().equals(finalTypeParser))
                            .findAny()
                            .map(Entry::value)
                            .orElse(null);

                    if (branchProcessor == null) {
                        branchProcessor = commandBranchProcessor;
                        CollectionHelpers.addElementSorted(
                                currentCommandOption.getOptionalArgumentTypeHandlerMap(),
                                new Entry<>(
                                    finalTypeParser,
                                    branchProcessor
                                ),
                                (a, b) -> b.key().compareTo(a.key())
                        );
                    }
                }

                currentCommandOption = createCommandOption(requirements, commandBranchProcessor);
                continue;

            }

            Map<String, CommandBranchProcessor> keywordMap = subCommand.ignoreCase()
                    ? currentCommandOption.getKeywordIgnoreCaseMap()
                    : currentCommandOption.getKeywordMap();

            CommandBranchProcessor commandBranchProcessor = keywordMap.computeIfAbsent(subCommand.ignoreCase() ? cmd.toLowerCase() : cmd, key -> new CommandBranchProcessor(commandManager));
            currentCommandOption = createCommandOption(requirements, commandBranchProcessor);
        }


        try {
            CollectionHelpers.addElementSorted(
                    currentCommandOption.getRegisteredCommandVariants(),
                    new RegisteredCommandVariant(
                        publicLookup.unreflect(method),
                        serializeParameters(node, method.getParameters()),
                        node,
                        subCommand.priority()),
                    REGISTERED_COMMAND_VARIANT_COMPARATOR
            );
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error accessing method", e);
        }
    }

    private RegisteredCommandVariant.Parameter[] serializeParameters(CommandNode commandNode, Parameter[] parameters) {
        RegisteredCommandVariant.Parameter[] result = new RegisteredCommandVariant.Parameter[parameters.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = new RegisteredCommandVariant.Parameter(
                    parameters[i].getType(),
                    parameters[i].getParameterizedType(),
                    serializeArgumentRequirement(commandNode, parameters[i].getAnnotations())
            );
        }
        return result;
    }

    private ArgumentRequirement[] serializeArgumentRequirement(CommandNode commandNode, Annotation[] annotations) {
        List<ArgumentRequirement> result = new ArrayList<>();

        for (Annotation annotation : annotations) {
            ArgumentRequirementType argumentRequirementType = annotation.annotationType().getAnnotation(ArgumentRequirementType.class);
            if (argumentRequirementType == null)
                continue;

            ArgumentRequirementFactory factory = commandManager.argumentRequirementFactories.computeIfAbsent(argumentRequirementType.value(), (clazz) -> {
                try {
                    return argumentRequirementType.value().getConstructor().newInstance();
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                    return null;
                }
            });

            if (factory == null)
                continue;

            ArgumentRequirement argumentRequirement = factory.getArgumentRequirement(commandNode, annotation);
            if (argumentRequirement == null)
                continue;

            result.add(argumentRequirement);
        }

        return result.toArray(new ArgumentRequirement[0]);
    }

    private CommandOption createCommandOption(RequirementSet requirements, CommandBranchProcessor commandBranchProcessor) {
        CommandOption cmdOption = commandBranchProcessor.objectMap.stream()
                .filter(entry -> entry.key().equals(requirements))
                .findAny()
                .map(Entry::value)
                .orElse(null);

        if (cmdOption == null) {
            cmdOption = new CommandOption(commandManager);
            commandBranchProcessor.objectMap.add(new Entry<>(
                    requirements,
                    cmdOption
            ));
        }

        return cmdOption;
    }

    private Set<Requirement> findAllRequirements(CommandNode commandNode, Class<?> clazz) {
        Set<Requirement> requirements = new HashSet<>();

        for (Class<?> c : ClassUtils.getSuperClassAndInterfaces(clazz)) {
            requirements.addAll(createRequirementSet(commandNode, c.getAnnotations()));
        }

        return requirements;
    }

    private Set<Requirement> findAllRequirements(CommandNode commandNode, Method method) {
        Set<Requirement> requirements = new HashSet<>(createRequirementSet(commandNode, method.getAnnotations()));
        for (Method target : MethodUtils.getMethodsFromSuperClassAndInterface(method)) {
            requirements.addAll(createRequirementSet(commandNode, target.getAnnotations()));
        }

        return requirements;
    }

    private Requirement getRequirement(CommandNode commandNode, Annotation annotation) {
        CommandRequirementType commandRequirementType = annotation.annotationType().getAnnotation(CommandRequirementType.class);
        if (commandRequirementType == null)
            return null;

        RequirementFactory requirementFactory = commandManager.requirementFactories.computeIfAbsent(commandRequirementType.value(), (clazz) -> {
            try {
                return commandRequirementType.value().getConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                return null;
            }
        });

        if (requirementFactory == null)
            return null;

        return requirementFactory.getRequirement(commandNode, annotation);
    }


    private Set<Requirement> createRequirementSet(CommandNode commandNode, Annotation[] annotations) {
        if (annotations == null || annotations.length == 0)
            return Collections.emptySet();

        Set<Requirement> requirements = new HashSet<>();

        for (Annotation annotation : annotations) {
            Requirement requirement = getRequirement(commandNode, annotation);
            if (requirement == null)
                continue;

            requirements.add(requirement);
        }

        return requirements;
    }

    private Map<String, ArgumentParser<?>> serializeArgumentTypeHandler(CommandNode commandNode, Annotation[] annotations) {
        Map<String, ArgumentParser<?>> argumentTypeHandlerMap = new HashMap<>();


        if (annotations == null || annotations.length == 0) {
            return argumentTypeHandlerMap;
        }

        for (Annotation annotation : annotations) {;
            if (annotation instanceof WithParser withParser) {
                ArgumentParser<?> argumentTypeHandler;
                try {
                    Constructor<? extends ArgumentParser<?>>[] ctors = (Constructor<? extends ArgumentParser<?>>[]) withParser.value().getDeclaredConstructors();
                    argumentTypeHandler = newInstance((Constructor<ArgumentParser<?>>[]) ctors, withParser.priority());
                } catch (InstantiationException |  IllegalAccessException | InvocationTargetException e) {
                    continue;
                }

                argumentTypeHandlerMap.put(argumentTypeHandler.getKeyword(), argumentTypeHandler);
                continue;
            }

            WithParserFactory withParserFactory = annotation.annotationType().getAnnotation(WithParserFactory.class);
            if (withParserFactory == null)
                continue;

            ParserFactory parserFactory = commandManager.parserFactories.computeIfAbsent(withParserFactory.value(), (clazz) -> {
                try {
                    return withParserFactory.value().getConstructor().newInstance();
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                    return null;
                }
            });

            if (parserFactory == null)
                continue;


            ArgumentParser<?> argumentParser = parserFactory.getArgumentParser(commandNode, annotation);
            if (argumentParser == null)
                continue;

            argumentTypeHandlerMap.put(argumentParser.getKeyword(), argumentParser);
        }

        return argumentTypeHandlerMap;
    }

    private static <T> T newInstance(Constructor<T>[] ctors, int priority) throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Constructor<?> constructor = null;

        if (ctors.length > 1) {
            for (Constructor<T> ctor : ctors) {
                if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0].equals(int.class))
                    constructor = ctor;
            }

        }

        if (constructor == null)
            constructor = ctors[0];

        Object[] params;
        if (constructor.getParameterCount() == 1) {
            params = new Object[] {priority};
        } else {
            if (priority > 0)
                SharedSecrets.LOGGER.log(System.Logger.Level.WARNING, "Registered parser {} with priority, but it doesn't support it", constructor.getDeclaringClass().getName());

            params = new Object[0];
        }
        return (T) constructor.newInstance(params);
    }

    public List<RegisteredCommandNode> getCommands() {
        return commands;
    }

    public CommandBranchProcessor getCommandBranchProcessor() {
        return commandBranchProcessor;
    }

    public Iterable<HandleExceptionVariant> getHandleExceptionVariants() {
        return handleExceptionVariants;
    }
}
