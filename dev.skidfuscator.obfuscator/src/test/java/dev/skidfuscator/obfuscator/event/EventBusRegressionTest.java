package dev.skidfuscator.obfuscator.event;

import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventBusRegressionTest {
    private static final List<Integer> calls = new ArrayList<>();
    private static final Event event = new Event(null) { };

    @BeforeEach void before() { EventBus.end(); calls.clear(); }
    @AfterEach void after() { EventBus.end(); }

    @Test void pollsInPriorityOrderForBothDispatchPaths() {
        EventBus.register(new Priorities());
        EventBus.call(event);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8), calls);
        calls.clear();
        EventBus.callButSkip(event);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8), calls);
    }

    @Test void equalPrioritiesRetainRegistrationOrder() {
        EventBus.register(new Second());
        EventBus.register(new First());
        EventBus.call(event);
        assertEquals(Arrays.asList(2, 1), calls);
    }

    @Test void transformerFailureStopsLaterListenersAndPreservesCause() {
        EventBus.register(new Broken());
        EventBus.register(new Second());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> EventBus.call(event));
        assertEquals("broken transformation", failure.getCause().getMessage());
        assertTrue(calls.isEmpty());
    }

    @Test void rejectsZeroArgumentListenerDuringRegistration() {
        assertThrows(IllegalStateException.class, () -> EventBus.register(new Invalid()));
    }

    static class First implements Listener { @Listen(10) void on(Event e) { calls.add(1); } }
    static class Second implements Listener { @Listen(10) void on(Event e) { calls.add(2); } }
    static class Broken implements Listener {
        @Listen(-1) void on(Event e) { throw new IllegalArgumentException("broken transformation"); }
    }
    static class Invalid implements Listener { @Listen void on() { } }
    static class Priorities implements Listener {
        @Listen(8) void a(Event e) { calls.add(8); }
        @Listen(1) void b(Event e) { calls.add(1); }
        @Listen(6) void c(Event e) { calls.add(6); }
        @Listen(3) void d(Event e) { calls.add(3); }
        @Listen(4) void e(Event e) { calls.add(4); }
        @Listen(5) void f(Event e) { calls.add(5); }
        @Listen(2) void g(Event e) { calls.add(2); }
        @Listen(7) void h(Event e) { calls.add(7); }
        @Listen(0) void i(Event e) { calls.add(0); }
    }
}
