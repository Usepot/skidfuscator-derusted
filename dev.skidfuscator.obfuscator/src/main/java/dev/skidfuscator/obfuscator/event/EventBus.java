package dev.skidfuscator.obfuscator.event;

import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.Event;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;

/**
 * Basic EventBus quickly mushed up together to serve as an excuse
 * to not use the visitor pattern.
 */
public class EventBus {
    private static final Map<Class<?>, List<EventListener>> listeners = new HashMap<>();
    private static long nextRegistrationId;
    private static final Comparator<EventListener> ORDER = Comparator.comparingInt(EventListener::getPriority)
            .thenComparingLong(listener -> listener.registrationId);

    /**
     * Registers a listener to the EventBus.
     *
     * @param instance Instance of the listener to be registered
     */
    public static void register(final Listener instance) {
        final Set<Method> methods = new HashSet<>();

        // get parent until abstract transformer is reached
        Class<?> clazz = instance.getClass();

        while (!clazz.equals(Listener.class) && !clazz.equals(Object.class)) {
            methods.addAll(Arrays.asList(clazz.getDeclaredMethods()));
            clazz = clazz.getSuperclass();
        }

        final List<Method> orderedMethods = new ArrayList<>(methods);
        orderedMethods.sort(Comparator.comparing(Method::toGenericString));
        for (Method declaredMethod : orderedMethods) {
            if (!declaredMethod.isAnnotationPresent(Listen.class))
                continue;

            if (!declaredMethod.isAccessible()) {
                declaredMethod.setAccessible(true);
            }

            if (declaredMethod.getParameterCount() != 1) {
                throw new IllegalStateException("Event listener must accept exactly one event: " + declaredMethod);
            }

            final Listen listen = declaredMethod.getAnnotation(Listen.class);

            final EventListener listener = new EventListener(
                    instance,
                    declaredMethod,
                    declaredMethod.getParameterTypes()[0],
                    listen.value()
            );

            List<EventListener> cached = listeners.computeIfAbsent(instance.getClass(), k -> new ArrayList<>());
            cached.add(listener);
        }
    }

    /**
     * Unregisters a specific listener from the EventBus.
     *
     * @param listener the instance of the listener to be removed
     */
    public static void unregister(final Object listener) {
        unregister(listener.getClass());
    }

    /**
     * Unregisters a specific class from the EventBus.
     *
     * @param listener the class of the listener to be removed
     */
    public static void unregister(final Class<?> listener) {
        listeners.remove(listener);
    }

    /**
     * Calls an event of type T
     *
     * @param <T>   the Type parameter of the event
     * @param event the event
     * @return Modified or intact output after passing through all the interceptors
     */
    public static <T extends Event> T call(final T event, Predicate<EventListener>... preconditions) {
        final Queue<EventListener> calls = new PriorityQueue<>(ORDER);

        for (List<EventListener> value : listeners.values()) {
            listenerLoop: for (EventListener listener : value) {
                for (Predicate<EventListener> precondition : preconditions) {
                    if (!precondition.test(listener)) continue listenerLoop;
                }
                if (listener.check(event)) calls.add(listener);
            }

        }

        // PriorityQueue iteration is heap order, not priority order.
        while (!calls.isEmpty()) {
            calls.remove().callUnsafe(event);
        }

        return event;
    }

    /**
     * Calls an event of type T
     *
     * @param <T>   the Type parameter of the event
     * @param event the event
     * @return Modified or intact output after passing through all the interceptors
     */
    public static <T extends Event> T callButSkip(final T event, final Class<?>... skipped) {
        final Queue<EventListener> calls = new PriorityQueue<>(ORDER);
        final Set<Class<?>> skippedSet = new HashSet<>(Arrays.asList(skipped));
        for (List<EventListener> value : listeners.values()) {
            for (EventListener listener : value) {
                if (skippedSet.contains(listener.method.getDeclaringClass()))
                    continue;

                if (listener.check(event)) calls.add(listener);
            }
        }

        // PriorityQueue iteration is heap order, not priority order.
        while (!calls.isEmpty()) {
            calls.remove().callUnsafe(event);
        }

        return event;
    }

    /**
     * Kills the EventBus and clears all of its listeners.
     */
    public static void end() {
        listeners.clear();
        nextRegistrationId = 0;
    }

    /**
     * Wrapper class for EventListener
     */
    public static class EventListener {
        private final Listener listener;
        private final Method method;
        private final Class<?> type;
        private final int priority;
        private final long registrationId;

        /**
         * Instantiates a new Event listener.
         *
         * @param listener the listener
         * @param method   the method
         * @param type     the type
         * @param priority the priority
         */
        public EventListener(Listener listener, Method method, Class<?> type, int priority) {
            this.listener = listener;
            this.method = method;
            this.type = type;
            this.priority = priority;
            this.registrationId = nextRegistrationId++;
        }

        /**
         * @return Returns the listener of this event
         */
        public Listener getListener() {
            return listener;
        }

        /**
         * @return type of the object of the listener
         */
        public Class<?> getType() {
            return type;
        }

        /**
         * @return the priority of the listener
         */
        public int getPriority() {
            return priority;
        }

        /**
         * @param event Object of an event
         * @return Returns a boolean of whether the listener can process the event
         */
        boolean check(final Object event) {
            return type.isInstance(event);
        }

        /**
         * Calls a specific event
         *
         * @param event Object of the event
         */
        void call(final Object event) {
            if (!check(event))
                return;

            callUnsafe(event);
        }

        /**
         * Calls an event using unsafe casting and reflections.
         *
         * @param event the object of the event
         */
        @Deprecated
        void callUnsafe(final Object event) {
            try {
                method.invoke(listener, event);
            } catch (InvocationTargetException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException("Event listener failed: " + method.toGenericString()
                        + " [" + event.getClass().getSimpleName() + "]", cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot invoke event listener: " + method.toGenericString(), e);
            }
        }
    }
}
