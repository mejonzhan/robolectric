package org.robolectric;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

public class HelperTestRunner extends BlockJUnit4ClassRunner {
    public HelperTestRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    @Override public Statement classBlock(RunNotifier notifier) {
        return super.classBlock(notifier);
    }

    @Override public Statement methodBlock(FrameworkMethod method) {
        return super.methodBlock(method);
    }
}
