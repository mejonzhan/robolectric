package org.robolectric.internal;

import org.robolectric.RobolectricContext;

import java.lang.reflect.Method;

public interface RobolectricTestRunnerInterface {
    void init(Class<?> bootstrappedTestClass, RobolectricContext robolectricContext);

    void internalBeforeTest(Method method);

    void internalAfterTest(Method method);

    void beforeTest(Method method);

    void afterTest(Method method);

    void prepareTest(Object test);

    public void setupApplicationState(Method testMethod);
}