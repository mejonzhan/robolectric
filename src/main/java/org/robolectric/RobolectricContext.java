package org.robolectric;

import org.apache.maven.artifact.ant.DependenciesTask;
import org.apache.maven.model.Dependency;
import org.apache.tools.ant.Project;
import org.robolectric.bytecode.*;
import org.robolectric.res.AndroidSdkFinder;
import org.robolectric.res.ResourcePath;

import java.io.File;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Hashtable;

public class RobolectricContext {
    private final AndroidManifest appManifest;
    private final ClassLoader robolectricClassLoader;
    private final ClassHandler classHandler;
    private ResourcePath systemResourcePath;

    public RobolectricContext() {
        Setup setup = createSetup();
        classHandler = createClassHandler(setup);
        appManifest = createAppManifest();
        robolectricClassLoader = createRobolectricClassLoader(setup);
    }

    private ClassHandler createClassHandler(Setup setup) {
        return new ShadowWrangler(setup);
    }

    public ClassCache createClassCache() {
        final String classCachePath = System.getProperty("cached.robolectric.classes.path");
        final File classCacheDirectory;
        if (null == classCachePath || "".equals(classCachePath.trim())) {
            classCacheDirectory = new File("./tmp");
        } else {
            classCacheDirectory = new File(classCachePath);
        }

        return new ZipClassCache(new File(classCacheDirectory, "cached-robolectric-classes.jar").getAbsolutePath(), AndroidTranslator.CACHE_VERSION);
    }

    public AndroidTranslator createAndroidTranslator(Setup setup, ClassCache classCache) {
        return new AndroidTranslator(classCache, setup);
    }

    protected AndroidManifest createAppManifest() {
        return new AndroidManifest(new File("."));
    }

    public AndroidManifest getAppManifest() {
        return appManifest;
    }

    public ClassHandler getClassHandler() {
        return classHandler;
    }

    public synchronized ResourcePath getSystemResourcePath() {
        if (systemResourcePath == null) {
            int targetSdkVersion = appManifest.getTargetSdkVersion();
            systemResourcePath = new AndroidSdkFinder().findSystemResourcePath(targetSdkVersion);
        }
        return systemResourcePath;
    }

    public Class<?> bootstrapTestClass(Class<?> testClass) {
        try {
            return robolectricClassLoader.loadClass(testClass.getName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    protected ClassLoader createRobolectricClassLoader(Setup setup) {
        URL[] urls = artifactUrls(realAndroidDependency("android-base"),
                realAndroidDependency("android-kxml2"),
                realAndroidDependency("android-luni"),
                createDependency("org.json", "json", "20080701", "jar", null)
        );
        ClassLoader robolectricClassLoader;
        if (useAsm()) {
            robolectricClassLoader = new AsmInstrumentingClassLoader(setup, urls);
        } else {
            ClassCache classCache = createClassCache();
            AndroidTranslator androidTranslator = createAndroidTranslator(setup, classCache);
            ClassLoader realSdkClassLoader = JavassistInstrumentingClassLoader.makeClassloader(this.getClass().getClassLoader(), urls);
            robolectricClassLoader = new JavassistInstrumentingClassLoader(realSdkClassLoader, classCache, androidTranslator, setup);
        }
        injectClassHandler(robolectricClassLoader);
        return robolectricClassLoader;
    }

    public boolean useAsm() {
        return true;
    }

    private void injectClassHandler(ClassLoader robolectricClassLoader) {
        try {
            String className = RobolectricInternals.class.getName();
            Class<?> robolectricInternalsClass = robolectricClassLoader.loadClass(className);
            Field field = robolectricInternalsClass.getDeclaredField("classHandler");
            field.setAccessible(true);
            field.set(null, classHandler);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public ClassLoader getRobolectricClassLoader() {
        return robolectricClassLoader;
    }

    public Setup createSetup() {
        return new Setup();
    }

    private URL[] artifactUrls(Dependency... dependencies) {
        DependenciesTask dependenciesTask = new DependenciesTask();
        configureMaven(dependenciesTask);
        Project project = new Project();
        dependenciesTask.setProject(project);
        for (Dependency dependency : dependencies) {
            dependenciesTask.addDependency(dependency);
        }
        dependenciesTask.execute();

        @SuppressWarnings("unchecked")
        Hashtable<String, String> artifacts = project.getProperties();
        URL[] urls = new URL[artifacts.size()];
        int i = 0;
        for (String path : artifacts.values()) {
            try {
                urls[i++] = new URL("file://" + path);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        return urls;
    }

    @SuppressWarnings("UnusedParameters")
    protected void configureMaven(DependenciesTask dependenciesTask) {
        // maybe you want to override this method and some settings?
    }

    private Dependency realAndroidDependency(String artifactId) {
        return createDependency("org.robolectric", artifactId, "4.1.2_r1_rc", "jar", "real");
    }

    private Dependency createDependency(String groupId, String artifactId, String version, String type, String classifier) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        dependency.setType(type);
        dependency.setClassifier(classifier);
        return dependency;
    }

    /**
     * @deprecated use {@link org.robolectric.Robolectric.Reflection#setFinalStaticField(Class, String, Object)}
     */
    public static void setStaticValue(Class<?> clazz, String fieldName, Object value) {
        Robolectric.Reflection.setFinalStaticField(clazz, fieldName, value);
    }
}
