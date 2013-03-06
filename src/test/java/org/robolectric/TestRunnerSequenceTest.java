package org.robolectric;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.model.InitializationError;
import org.robolectric.bytecode.ShadowMap;
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
            super(testClass);
        }

        @Override protected Class<? extends DefaultTestLifecycle> getTestLifecycleClass() {
            return MyTestLifecycle.class;
        }

        @Override protected synchronized ShadowMap createShadowMap() {
            transcript.add("configureShadows");
            return super.createShadowMap();
        }

        public static class MyTestLifecycle extends DefaultTestLifecycle {
            @Override public void beforeTest(Method method) {
                transcript.add("beforeTest");
            }

//            @Override protected void resetStaticState() {
//                transcript.add("resetStaticState");
//            }

            @Override
            public void setupApplicationState(Method testMethod) {
                transcript.add("setupApplicationState");
            }
        }
    }
}
