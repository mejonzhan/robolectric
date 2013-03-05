package org.robolectric;

import android.app.Application;
import android.content.res.Resources;
import org.robolectric.bytecode.ClassHandler;
import org.robolectric.internal.RobolectricTestRunnerInterface;
import org.robolectric.res.ResourceLoader;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowResources;
import org.robolectric.util.DatabaseConfig;

import java.lang.reflect.Method;

import static org.robolectric.Robolectric.shadowOf;

public class DefaultTestLifecycle implements RobolectricTestRunnerInterface {
    private RobolectricContext robolectricContext;

    @Override public void init(RobolectricContext robolectricContext) {
        this.robolectricContext = robolectricContext;
    }

    /*
                     * Called before each test method is run. Sets up the simulation of the Android runtime environment.
                     */
    @Override final public void internalBeforeTest(final Method method, DatabaseConfig.DatabaseMap databaseMap) {
        resetStaticState();

        DatabaseConfig.setDatabaseMap(databaseMap); //Set static DatabaseMap in DBConfig

        setupApplicationState(method);

        beforeTest(method);
    }

    @Override public void internalAfterTest(final Method method) {
        afterTest(method);
    }

    /**
     * Called before each test method is run.
     *
     * @param method the test method about to be run
     */
    public void beforeTest(final Method method) {
    }

    /**
     * Called after each test method is run.
     *
     * @param method the test method that just ran.
     */
    public void afterTest(final Method method) {
    }

    public void prepareTest(final Object test) {
    }

    public void setupApplicationState(Method testMethod) {
        boolean strictI18n = RobolectricTestRunner.determineI18nStrictState(testMethod);

        ResourceLoader systemResourceLoader = RobolectricTestRunner.getSystemResourceLoader(robolectricContext.getSystemResourcePath());
        ShadowResources.setSystemResources(systemResourceLoader);

        ClassHandler classHandler = robolectricContext.getClassHandler();
        classHandler.setStrictI18n(strictI18n);

        AndroidManifest appManifest = robolectricContext.getAppManifest();
        ResourceLoader resourceLoader = RobolectricTestRunner.getAppResourceLoader(systemResourceLoader, appManifest);

        Robolectric.application = ShadowApplication.bind(createApplication(), appManifest, resourceLoader);
        shadowOf(Robolectric.application).setStrictI18n(strictI18n);

        String qualifiers = RobolectricTestRunner.determineResourceQualifiers(testMethod);
        shadowOf(Resources.getSystem().getConfiguration()).overrideQualifiers(qualifiers);
        shadowOf(Robolectric.application.getResources().getConfiguration()).overrideQualifiers(qualifiers);
    }

    /**
     * Override this method to bind your own shadow classes
     */
    @SuppressWarnings("UnusedParameters")
    protected void bindShadowClasses(Method testMethod) {
        bindShadowClasses();
    }

    /**
     * Override this method to bind your own shadow classes
     */
    protected void bindShadowClasses() {
    }

    /**
     * Override this method to reset the state of static members before each test.
     */
    protected void resetStaticState() {
        Robolectric.resetStaticState();
    }

    /**
     * Override this method if you want to provide your own implementation of Application.
     * <p/>
     * This method attempts to instantiate an application instance as specified by the AndroidManifest.xml.
     *
     * @return An instance of the Application class specified by the ApplicationManifest.xml or an instance of
     *         Application if not specified.
     */
    protected Application createApplication() {
        return new ApplicationResolver(robolectricContext.getAppManifest()).resolveApplication();
    }
}
