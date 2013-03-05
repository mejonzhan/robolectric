package org.robolectric.bytecode;

import org.robolectric.internal.Implements;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ShadowMap {
    private static Set<String> unloadableClassNames = new HashSet<String>();
    private Map<String, ShadowConfig> shadowClassMap = new HashMap<String, ShadowConfig>();

    public void addShadowClass(Class<?> shadowClass) {
        Implements implementsAnnotation = shadowClass.getAnnotation(Implements.class);
        if (implementsAnnotation == null) {
            throw new IllegalArgumentException(shadowClass + " is not annotated with @Implements");
        }

        try {
            String className = implementsAnnotation.value().getName();
            if (!implementsAnnotation.className().isEmpty()) {
                className = implementsAnnotation.className();
            }
            addShadowClass(className, shadowClass, implementsAnnotation.callThroughByDefault());
        } catch (TypeNotPresentException typeLoadingException) {
            String unloadableClassName = shadowClass.getSimpleName();
            if (typeLoadingException.typeName().startsWith("com.google.android.maps")) {
                warnAbout(unloadableClassName);
                return;
            } else if (isIgnorableClassLoadingException(typeLoadingException)) {
                //this allows users of the robolectric.jar file to use the non-Google APIs version of the api
                warnAbout(unloadableClassName);
            } else {
                throw typeLoadingException;
            }
        }
    }

    public void addShadowClass(String realClassName, Class<?> shadowClass, boolean callThroughByDefault) {
        addShadowClass(realClassName, shadowClass.getName(), callThroughByDefault);
    }

    public void addShadowClass(Class<?> realClass, Class<?> shadowClass, boolean callThroughByDefault) {
        addShadowClass(realClass.getName(), shadowClass.getName(), callThroughByDefault);
    }

    public void addShadowClass(String realClassName, String shadowClassName, boolean callThroughByDefault) {
        shadowClassMap.put(realClassName, new ShadowConfig(shadowClassName, callThroughByDefault));
    }

    private static boolean isIgnorableClassLoadingException(Throwable typeLoadingException) {
        if (typeLoadingException != null) {
            // instanceof doesn't work here. Are we in different classloaders?
            if (typeLoadingException.getClass().getName().equals(IgnorableClassNotFoundException.class.getName())) {
                return true;
            }

            if (typeLoadingException instanceof NoClassDefFoundError
                    || typeLoadingException instanceof ClassNotFoundException
                    || typeLoadingException instanceof TypeNotPresentException) {
                return isIgnorableClassLoadingException(typeLoadingException.getCause());
            }
        }
        return false;
    }

    private static void warnAbout(String unloadableClassName) {
        if (unloadableClassNames.add(unloadableClassName)) {
            System.out.println("Warning: an error occurred while binding shadow class: " + unloadableClassName);
        }
    }

    public ShadowConfig get(String name) {
        return shadowClassMap.get(name);
    }
}
