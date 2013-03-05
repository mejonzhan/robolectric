package org.robolectric.internal;

import org.robolectric.RobolectricContext;
import org.robolectric.util.DatabaseConfig;

import java.lang.reflect.Method;

public interface RobolectricTestRunnerInterface {
    void init(Class<?> bootstrappedTestClass, RobolectricContext robolectricContext);

    void internalBeforeTest(Method method, DatabaseConfig.DatabaseMap databaseMap1);

    void internalAfterTest(Method method);

    void beforeTest(Method method);

    void afterTest(Method method);

    void prepareTest(Object test);

    public void setupApplicationState(Method testMethod);
}