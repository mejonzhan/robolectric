package org.robolectric;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.model.InitializationError;
import org.robolectric.util.Transcript;

import java.lang.reflect.Method;

@RunWith(TestRunnerSequenceTest.Runner.class)
public class TestRunnerSequenceTest {
    public static Transcript transcript = new Transcript();

    @Test public void shouldRunThingsInTheRightOrder() throws Exception {
        transcript.assertEventsSoFar(
                "configureShadows",
                "resetStaticState",
                "setupApplicationState",
                "beforeTest"
        );
    }

    public static class Runner extends RobolectricTestRunner {
        public Runner(Class<?> testClass) throws InitializationError {
            super(RobolectricContext.bootstrap(Runner.class, testClass, new RobolectricContext.Factory() {
                @Override
                public RobolectricContext create() {
                    return new RobolectricContext();
                }
            }));
        }

        @Override protected Class<? extends TestLifecycle> getTestLifecycleClass() {
            return MyTestLifecycle.class;
        }

        private class MyTestLifecycle extends TestLifecycle {
            @Override public void beforeTest(Method method) {
                transcript.add("beforeTest");
            }

            @Override protected void resetStaticState() {
                transcript.add("resetStaticState");
            }

            @Override protected void configureShadows(Method testMethod) {
                transcript.add("configureShadows");
            }

            @Override
            public void setupApplicationState(Method testMethod) {
                transcript.add("setupApplicationState");
            }
        }
    }
}
