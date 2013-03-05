package org.robolectric;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.robolectric.annotation.*;
import org.robolectric.bytecode.InstrumentingClassLoader;
import org.robolectric.bytecode.ShadowMap;
import org.robolectric.bytecode.ShadowWrangler;
import org.robolectric.internal.RobolectricTestRunnerInterface;
import org.robolectric.res.*;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.DatabaseConfig.DatabaseMap;
import org.robolectric.util.DatabaseConfig.UsingDatabaseMap;
import org.robolectric.util.SQLiteMap;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Installs a {@link org.robolectric.bytecode.InstrumentingClassLoader} and
 * {@link org.robolectric.res.ResourceLoader} in order to
 * provide a simulation of the Android runtime environment.
 */
public class RobolectricTestRunner extends BlockJUnit4ClassRunner {
    private static final Map<Class<? extends RobolectricTestRunner>, RobolectricContext> contextsByTestRunner = new WeakHashMap<Class<? extends RobolectricTestRunner>, RobolectricContext>();
    private static final Map<AndroidManifest, ResourceLoader> resourceLoadersByAppManifest = new HashMap<AndroidManifest, ResourceLoader>();
    private static final Map<ResourcePath, ResourceLoader> systemResourceLoaders = new HashMap<ResourcePath, ResourceLoader>();

    private static ShadowMap shadowMap;

    private RobolectricContext robolectricContext;
    private DatabaseMap databaseMap;
    private RobolectricTestRunnerInterface testLifecycle;

    /**
     * Creates a runner to run {@code testClass}. Looks in your working directory for your AndroidManifest.xml file
     * and res directory.
     *
     * @param testClass the test class to be run
     * @throws InitializationError if junit says so
     */
    public RobolectricTestRunner(final Class<?> testClass) throws InitializationError {
        super(testClass);

        RobolectricContext robolectricContext;
        synchronized (contextsByTestRunner) {
            Class<? extends RobolectricTestRunner> testRunnerClass = getClass();
            robolectricContext = contextsByTestRunner.get(testRunnerClass);
            if (robolectricContext == null) {
                try {
                    robolectricContext = createRobolectricContext();
                    System.out.println("Created " + robolectricContext + " for " + testRunnerClass);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                contextsByTestRunner.put(testRunnerClass, robolectricContext);
            } else {
                System.out.println("Used " + robolectricContext + " for " + testRunnerClass);
            }
        }
        this.robolectricContext = robolectricContext;


        try {
            testLifecycle = (RobolectricTestRunnerInterface) robolectricContext.getRobolectricClassLoader().loadClass(getTestLifecycleClass().getName()).newInstance();
            testLifecycle.init(robolectricContext);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        databaseMap = setupDatabaseMap(testClass, new SQLiteMap());
        Thread.currentThread().setContextClassLoader(robolectricContext.getRobolectricClassLoader());
    }

    public RobolectricContext createRobolectricContext() {
        return new RobolectricContext();
    }

    protected Class<? extends DefaultTestLifecycle> getTestLifecycleClass() {
        return DefaultTestLifecycle.class;
    }

    public RobolectricContext getRobolectricContext() {
        return robolectricContext;
    }

    protected static boolean isBootstrapped(Class<?> clazz) {
        return clazz.getClassLoader() instanceof InstrumentingClassLoader;
    }

    @Override
    protected Statement classBlock(RunNotifier notifier) {
        final Statement statement = super.classBlock(notifier);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    statement.evaluate();
                } finally {
                    afterClass();
                }
            }
        };
    }

    @Override protected Statement methodBlock(final FrameworkMethod method) {
        robolectricContext.getClassHandler().reset();

        Class bootstrappedTestClass = robolectricContext.bootstrapTestClass(getTestClass().getJavaClass());
        HelperTestRunner helperTestRunner;
        try {
            helperTestRunner = new HelperTestRunner(bootstrappedTestClass);
        } catch (InitializationError initializationError) {
            throw new RuntimeException(initializationError);
        }

        setupLogging();
        ShadowMap shadowMap = createShadowMap();
        ((ShadowWrangler) robolectricContext.getClassHandler()).setShadowMap(shadowMap);

        try {
        testLifecycle.internalBeforeTest(method.getMethod(), databaseMap);
      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }

        Method bootstrappedMethod;
        try {
            bootstrappedMethod = bootstrappedTestClass.getMethod(method.getName());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        final Statement statement = helperTestRunner.methodBlock(new FrameworkMethod(bootstrappedMethod));
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                Map<Field, Object> withConstantAnnos = getWithConstantAnnotations(method.getMethod());

            	// todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
                try {
                	if (withConstantAnnos.isEmpty()) {
                		statement.evaluate();
                	}
                	else {
                		synchronized(this) {
	                		setupConstants(withConstantAnnos);
	                		statement.evaluate();
	                		setupConstants(withConstantAnnos);
                		}
                	}
                } finally {
                    testLifecycle.internalAfterTest(method.getMethod());
                }
            }
        };
    }


    private void afterClass() {
        System.out.println("after class!");
        testLifecycle = null;
        robolectricContext = null;
        databaseMap = null;
    }

    /**
     * You probably don't want to override this method. Override #prepareTest(Object) instead.
     *
     * @see BlockJUnit4ClassRunner#createTest()
     */
    @Override
    public Object createTest() throws Exception {
        Object test = super.createTest();
        testLifecycle.prepareTest(test);
        return test;
    }

    public static String determineResourceQualifiers(Method method) {
        String qualifiers = "";
        Values values = method.getAnnotation(Values.class);
        if (values != null) {
            qualifiers = values.qualifiers();
        }
        return qualifiers;
    }

    /**
     * Sets Robolectric config to determine if Robolectric should blacklist API calls that are not
     * I18N/L10N-safe.
     * <p/>
     * I18n-strict mode affects suitably annotated shadow methods. Robolectric will throw exceptions
     * if these methods are invoked by application code. Additionally, Robolectric's ResourceLoader
     * will throw exceptions if layout resources use bare string literals instead of string resource IDs.
     * <p/>
     * To enable or disable i18n-strict mode for specific test cases, annotate them with
     * {@link org.robolectric.annotation.EnableStrictI18n} or
     * {@link org.robolectric.annotation.DisableStrictI18n}.
     * <p/>
     *
     * By default, I18n-strict mode is disabled.
     *
     * @param method
     *
     */
    public static boolean determineI18nStrictState(Method method) {
    	// Global
    	boolean strictI18n = globalI18nStrictEnabled();

    	// Test case class
        Class<?> testClass = method.getDeclaringClass();
        if (testClass.getAnnotation(EnableStrictI18n.class) != null) {
            strictI18n = true;
        } else if (testClass.getAnnotation(DisableStrictI18n.class) != null) {
            strictI18n = false;
        }

    	// Test case method
        if (method.getAnnotation(EnableStrictI18n.class) != null) {
            strictI18n = true;
        } else if (method.getAnnotation(DisableStrictI18n.class) != null) {
            strictI18n = false;
        }

		return strictI18n;
    }

    /**
     * Default implementation of global switch for i18n-strict mode.
     * To enable i18n-strict mode globally, set the system property
     * "robolectric.strictI18n" to true. This can be done via java
     * system properties in either Ant or Maven.
     * <p/>
     * Subclasses can override this method and establish their own policy
     * for enabling i18n-strict mode.
     *
     * @return
     */
    protected static boolean globalI18nStrictEnabled() {
    	return Boolean.valueOf(System.getProperty("robolectric.strictI18n"));
    }

    /**
	 * Find all the class and method annotations and pass them to
	 * addConstantFromAnnotation() for evaluation.
	 *
	 * TODO: Add compound annotations to support defining more than one int and string at a time
	 * TODO: See http://stackoverflow.com/questions/1554112/multiple-annotations-of-the-same-type-on-one-element
	 *
	 * @param method
	 * @return
	 */
    private Map<Field, Object> getWithConstantAnnotations(Method method) {
        Map<Field, Object> constants = new HashMap<Field, Object>();

        for (Annotation anno : method.getDeclaringClass().getAnnotations()) {
            addConstantFromAnnotation(constants, anno);
        }

        for (Annotation anno : method.getAnnotations()) {
            addConstantFromAnnotation(constants, anno);
        }

        return constants;
    }


    /**
     * If the annotation is a constant redefinition, add it to the provided hash
     *
     * @param constants
     * @param anno
     */
    private void addConstantFromAnnotation(Map<Field,Object> constants, Annotation anno) {
        try {
        	String name = anno.annotationType().getName();
        	Object newValue = null;

	    	if (name.equals(WithConstantString.class.getName())) {
	    		newValue = (String) anno.annotationType().getMethod("newValue").invoke(anno);
	    	}
	    	else if (name.equals(WithConstantInt.class.getName())) {
	    		newValue = (Integer) anno.annotationType().getMethod("newValue").invoke(anno);
	    	}
	    	else {
	    		return;
	    	}

    		@SuppressWarnings("rawtypes")
			Class classWithField = (Class) anno.annotationType().getMethod("classWithField").invoke(anno);
    		String fieldName = (String) anno.annotationType().getMethod("fieldName").invoke(anno);
            Field field = classWithField.getDeclaredField(fieldName);
            constants.put(field, newValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Defines static finals from the provided hash and stores the old values back
     * into the hash.
     *
     * Call it twice with the same hash, and it puts everything back the way it was originally.
     *
     * @param constants
     */
    private void setupConstants(Map<Field,Object> constants) {
        for (Field field : constants.keySet()) {
            Object newValue = constants.get(field);
            Object oldValue = Robolectric.Reflection.setFinalStaticField(field, newValue);
            constants.put(field, oldValue);
        }
    }

    public static ResourceLoader getSystemResourceLoader(ResourcePath systemResourcePath) {
        ResourceLoader systemResourceLoader = systemResourceLoaders.get(systemResourcePath);
        if (systemResourceLoader == null) {
            systemResourceLoader = createResourceLoader(systemResourcePath);
            systemResourceLoaders.put(systemResourcePath, systemResourceLoader);
        }
        return systemResourceLoader;
    }

    public static ResourceLoader getAppResourceLoader(ResourceLoader systemResourceLoader, final AndroidManifest appManifest) {
        ResourceLoader resourceLoader = resourceLoadersByAppManifest.get(appManifest);
        if (resourceLoader == null) {
            resourceLoader = createAppResourceLoader(systemResourceLoader, appManifest);
            resourceLoadersByAppManifest.put(appManifest, resourceLoader);
        }
        return resourceLoader;
    }

    // this method must live on a InstrumentingClassLoader-loaded class, so it can't be on RobolectricContext
    protected static ResourceLoader createAppResourceLoader(ResourceLoader systemResourceLoader, AndroidManifest appManifest) {
        List<PackageResourceLoader> appAndLibraryResourceLoaders = new ArrayList<PackageResourceLoader>();
        for (ResourcePath resourcePath : appManifest.getIncludedResourcePaths()) {
            appAndLibraryResourceLoaders.add(new PackageResourceLoader(resourcePath));
        }
        OverlayResourceLoader overlayResourceLoader = new OverlayResourceLoader(appManifest.getPackageName(), appAndLibraryResourceLoaders);

        Map<String, ResourceLoader> resourceLoaders = new HashMap<String, ResourceLoader>();
        resourceLoaders.put("android", systemResourceLoader);
        resourceLoaders.put(appManifest.getPackageName(), overlayResourceLoader);
        return new RoutingResourceLoader(resourceLoaders);
    }

    public static PackageResourceLoader createResourceLoader(ResourcePath systemResourcePath) {
        return new PackageResourceLoader(systemResourcePath);
    }

    /*
     * Specifies what database to use for testing (ex: H2 or Sqlite),
     * this will load H2 by default, the SQLite TestRunner version will override this.
     */
    protected DatabaseMap setupDatabaseMap(Class<?> testClass, DatabaseMap map) {
    	DatabaseMap dbMap = map;

    	if (testClass.isAnnotationPresent(UsingDatabaseMap.class)) {
	    	UsingDatabaseMap usingMap = testClass.getAnnotation(UsingDatabaseMap.class);
	    	if(usingMap.value()!=null){
	    		dbMap = Robolectric.newInstanceOf(usingMap.value());
	    	} else {
	    		if (dbMap==null)
		    		throw new RuntimeException("UsingDatabaseMap annotation value must provide a class implementing DatabaseMap");
	    	}
    	}
    	return dbMap;
    }

    private synchronized ShadowMap createShadowMap() {
        if (shadowMap != null) return shadowMap;

        shadowMap = new ShadowMap();
        for (Class<?> shadowClass : RobolectricBase.DEFAULT_SHADOW_CLASSES) {
            shadowMap.addShadowClass(shadowClass);
        }
        return shadowMap;
    }


    private void setupLogging() {
        String logging = System.getProperty("robolectric.logging");
        if (logging != null && ShadowLog.stream == null) {
            PrintStream stream = null;
            if ("stdout".equalsIgnoreCase(logging)) {
                stream = System.out;
            } else if ("stderr".equalsIgnoreCase(logging)) {
                stream = System.err;
            } else {
                try {
                    final PrintStream file = new PrintStream(new FileOutputStream(logging));
                    stream = file;
                    Runtime.getRuntime().addShutdownHook(new Thread() {
                        @Override public void run() {
                            try { file.close(); } catch (Exception ignored) { }
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            ShadowLog.stream = stream;
        }
    }
}
