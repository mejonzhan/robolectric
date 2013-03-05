package org.robolectric.bytecode;

import android.support.v4.content.LocalBroadcastManager;
import org.robolectric.internal.Implementation;
import org.robolectric.internal.RealObject;
import org.robolectric.util.Function;
import org.robolectric.util.I18nException;
import org.robolectric.util.Join;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

public class ShadowWrangler implements ClassHandler {
    public static final Function<Object, Object> DO_NOTHING_HANDLER = new Function<Object, Object>() {
        @Override
        public Object call(Object value) {
            return null;
        }
    };
    private static final int MAX_CALL_DEPTH = 200;
    private static final boolean STRIP_SHADOW_STACK_TRACES = true;

    private final Setup setup;

    public boolean debug = false;
    private boolean strictI18n = false;

    private final Map<InvocationProfile, InvocationPlan> invocationPlans = new LinkedHashMap<InvocationProfile, InvocationPlan>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<InvocationProfile, InvocationPlan> eldest) {
            return size() > 500;
        }
    };
    private final Map<Class, MetaShadow> metaShadowMap = new HashMap<Class, MetaShadow>();
    private ShadowMap shadowClassMap = null;
    private boolean logMissingShadowMethods = false;
    private static ThreadLocal<Info> infos = new ThreadLocal<Info>() {
        @Override
        protected Info initialValue() {
            return new Info();
        }
    };

    public void setShadowMap(ShadowMap shadowMap) {
        this.shadowClassMap = shadowMap;
    }

    private static class Info {
        private int callDepth = 0;
    }

    public ShadowWrangler(Setup setup) {
        this.setup = setup;
    }

    @Override
    public void setStrictI18n(boolean strictI18n) {
        this.strictI18n = strictI18n;
    }

    @Override
    public void reset() {
//   todo     shadowClassMap.clear();
    }

    @Override
    public void classInitializing(Class clazz) {
        Class<?> shadowClass = findDirectShadowClass(clazz);
        if (shadowClass != null) {
            try {
                Method method = shadowClass.getMethod(InstrumentingClassLoader.STATIC_INITIALIZER_METHOD_NAME);
                if (!Modifier.isStatic(method.getModifiers())) {
                    throw new RuntimeException(shadowClass.getName() + "." + method.getName() + " is not static");
                }
                method.setAccessible(true);
                method.invoke(null);
            } catch (NoSuchMethodException e) {
                if (setup.shouldPerformStaticInitializationIfShadowIsMissing()) {
                    RobolectricInternals.performStaticInitialization(clazz);
                }
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        } else {
            RobolectricInternals.performStaticInitialization(clazz);
        }
    }

    private String indent(int count) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < count; i++) buf.append("  ");
        return buf.toString();
    }

    class InvocationProfile {
        private final Class clazz;
        private final Class shadowClass;
        private final String methodName;
        private final boolean isStatic;
        private final String[] paramTypes;
        private final int hashCode;

        InvocationProfile(Class clazz, Class shadowClass, String methodName, boolean aStatic, String[] paramTypes) {
            this.clazz = clazz;
            this.shadowClass = shadowClass;
            this.methodName = methodName;
            isStatic = aStatic;
            this.paramTypes = paramTypes;

            // calculate hashCode early
            int result = clazz.hashCode();
            result = 31 * result + (shadowClass != null ? shadowClass.hashCode() : 0);
            result = 31 * result + methodName.hashCode();
            result = 31 * result + (isStatic ? 1 : 0);
            result = 31 * result + Arrays.hashCode(paramTypes);
            hashCode = result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            InvocationProfile that = (InvocationProfile) o;

            if (isStatic != that.isStatic) return false;
            if (!clazz.equals(that.clazz)) return false;
            if (!methodName.equals(that.methodName)) return false;
            if (!Arrays.equals(paramTypes, that.paramTypes)) return false;
            if (shadowClass != null ? !shadowClass.equals(that.shadowClass) : that.shadowClass != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    @Override
    public Object methodInvoked(Class clazz, String methodName, Object instance, String[] paramTypes, Object[] params) throws Exception {
        Info info = infos.get();
        if (info.callDepth > MAX_CALL_DEPTH) throw stripStackTrace(new StackOverflowError("too deep!"));
        try {
            info.callDepth++;
            InvocationPlan invocationPlan = getInvocationPlan(clazz, methodName, instance, paramTypes);
            try {
                boolean hasShadowImplementation = invocationPlan.hasShadowImplementation();
                if (debug) {
                    System.out.println(indent(info.callDepth) + " -> " +
                            clazz.getName() + "." + methodName + "(" + Join.join(", ", paramTypes) + "): "
                            + (hasShadowImplementation ? "shadowed by " + (instance == null ? "?" : invocationPlan.getDeclaredShadowClass().getName()) : "direct"));
                }

                if (!hasShadowImplementation) {
                    reportNoShadowMethodFound(clazz, methodName, paramTypes);
                    if (invocationPlan.shouldDelegateToRealMethodWhenMethodShadowIsMissing()) {
                        return invocationPlan.callOriginal(instance, params);
                    } else {
                        return null;
                    }
                }

                // todo: a little strange that this lives here...
                if (strictI18n && !invocationPlan.isI18nSafe()) {
                    throw new I18nException("Method " + methodName + " on class " + clazz.getName() + " is not i18n-safe.");
                }

                return invocationPlan.getMethod().invoke(instance == null ? null : shadowOf(instance), params);
            } catch (IllegalArgumentException e) {
                Object shadow = instance == null ? null : shadowOf(instance);
                Class<? extends Object> aClass = shadow == null ? null : shadow.getClass();
                String aClassName = aClass == null ? "<unknown class>" : aClass.getName();
                throw new RuntimeException(aClassName + " is not assignable from " +
                        invocationPlan.getDeclaredShadowClass().getName(), e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw stripStackTrace((Exception) cause);
                }
                throw new RuntimeException(cause);
            }
        } finally {
            info.callDepth--;
        }
    }

    private InvocationPlan getInvocationPlan(Class clazz, String methodName, Object instance, String[] paramTypes) {
        boolean isStatic = instance == null;
        Class shadowClass = isStatic ? findDirectShadowClass(clazz) : shadowOf(instance).getClass();
        InvocationProfile invocationProfile = new InvocationProfile(clazz, shadowClass, methodName, isStatic, paramTypes);
        synchronized (invocationPlans) {
            InvocationPlan invocationPlan = invocationPlans.get(invocationProfile);
            if (invocationPlan == null) {
                invocationPlan = new InvocationPlan(invocationProfile);
                invocationPlans.put(invocationProfile, invocationPlan);
            }
            return invocationPlan;
        }
    }

    @Override
    public Object intercept(String className, String methodName, Object instance, Object[] paramTypes, Object[] params) throws Throwable {
        if (debug)
            System.out.println("DEBUG: intercepted call to " + className + "." + methodName + "(" + Join.join(", ", params) + ")");

        return getInterceptionHandler(className, methodName).call(instance);
    }

    public Function<Object, Object> getInterceptionHandler(String className, String methodName) {
        className = className.replace('/', '.');

        if (className.equals(LinkedHashMap.class.getName()) && methodName.equals("eldest")) {
            return new Function<Object, Object>() {
                @Override
                public Object call(Object value) {
                    LinkedHashMap map = (LinkedHashMap) value;
                    return map.entrySet().iterator().next();
                }
            };
        }

        return ShadowWrangler.DO_NOTHING_HANDLER;
    }

    private <T extends Throwable> T stripStackTrace(T throwable) {
        if (STRIP_SHADOW_STACK_TRACES) {
            List<StackTraceElement> stackTrace = new ArrayList<StackTraceElement>();
            for (StackTraceElement stackTraceElement : throwable.getStackTrace()) {
                String className = stackTraceElement.getClassName();
                boolean isInternalCall = className.startsWith("sun.reflect.")
                        || className.startsWith("java.lang.reflect.")
                        || className.equals(ShadowWrangler.class.getName())
                        || className.equals(RobolectricInternals.class.getName());
                if (!isInternalCall) {
                    stackTrace.add(stackTraceElement);
                }
            }
            throwable.setStackTrace(stackTrace.toArray(new StackTraceElement[stackTrace.size()]));
        }
        return throwable;
    }

    private void reportNoShadowMethodFound(Class clazz, String methodName, String[] paramTypes) {
        if (logMissingShadowMethods) {
            System.out.println("No Shadow method found for " + clazz.getSimpleName() + "." + methodName + "(" +
                    Join.join(", ", (Object[]) paramTypes) + ")");
        }
    }

    public static Class<?> loadClass(String paramType, ClassLoader classLoader) {
        Class primitiveClass = RoboType.findPrimitiveClass(paramType);
        if (primitiveClass != null) return primitiveClass;

        int arrayLevel = 0;
        while (paramType.endsWith("[]")) {
            arrayLevel++;
            paramType = paramType.substring(0, paramType.length() - 2);
        }

        Class<?> clazz = RoboType.findPrimitiveClass(paramType);
        if (clazz == null) {
            try {
                clazz = classLoader.loadClass(paramType);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        while (arrayLevel-- > 0) {
            clazz = Array.newInstance(clazz, 0).getClass();
        }

        return clazz;
    }

    public Object shadowFor(Object instance) {
        Field field = RobolectricInternals.getShadowField(instance);
        field.setAccessible(true);
        Object shadow = readField(instance, field);

        if (shadow != null) {
            return shadow;
        }

        String shadowClassName = getShadowClassName(instance.getClass());

        if (shadowClassName == null) return new Object();

        if (debug)
            System.out.println("creating new " + shadowClassName + " as shadow for " + instance.getClass().getName());
        try {
            Class<?> shadowClass = loadClass(shadowClassName, instance.getClass().getClassLoader());
            Constructor<?> constructor = findConstructor(instance, shadowClass);
            if (constructor != null) {
                shadow = constructor.newInstance(instance);
            } else {
                shadow = shadowClass.newInstance();
            }
            field.set(instance, shadow);

            injectRealObjectOn(shadow, shadowClass, instance);

            return shadow;
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private void injectRealObjectOn(Object shadow, Class<?> shadowClass, Object instance) {
        MetaShadow metaShadow = getMetaShadow(shadowClass);
        for (Field realObjectField : metaShadow.realObjectFields) {
            writeField(shadow, instance, realObjectField);
        }
    }

    private MetaShadow getMetaShadow(Class<?> shadowClass) {
        synchronized (metaShadowMap) {
            MetaShadow metaShadow = metaShadowMap.get(shadowClass);
            if (metaShadow == null) {
                metaShadow = new MetaShadow(shadowClass);
                metaShadowMap.put(shadowClass, metaShadow);
            }
            return metaShadow;
        }
    }

    private Class<?> findDirectShadowClass(Class<?> originalClass) {
        ShadowConfig shadowConfig = shadowClassMap.get(originalClass.getName());
        if (shadowConfig == null) {
            return null;
        }
        return loadClass(shadowConfig.shadowClassName, originalClass.getClassLoader());
    }

    private String getShadowClassName(Class clazz) {
        ShadowConfig shadowConfig = null;
        while (shadowConfig == null && clazz != null) {
            shadowConfig = shadowClassMap.get(clazz.getName());
            clazz = clazz.getSuperclass();
        }
        return shadowConfig == null ? null : shadowConfig.shadowClassName;
    }

    private Constructor<?> findConstructor(Object instance, Class<?> shadowClass) {
        Class clazz = instance.getClass();

        Constructor constructor;
        for (constructor = null; constructor == null && clazz != null; clazz = clazz.getSuperclass()) {
            try {
                constructor = shadowClass.getConstructor(clazz);
            } catch (NoSuchMethodException e) {
                // expected
            }
        }
        return constructor;
    }

    public Object shadowOf(Object instance) {
        if (instance == null) {
            throw new NullPointerException("can't get a shadow for null");
        }
        Field field = RobolectricInternals.getShadowField(instance);
        Object shadow = readField(instance, field);
        if (shadow == null) {
            shadow = shadowFor(instance);
        }
        return shadow;
    }

    private Object readField(Object target, Field field) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e1) {
            throw new RuntimeException(e1);
        }
    }

    private void writeField(Object target, Object value, Field realObjectField) {
        try {
            realObjectField.set(target, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public void logMissingInvokedShadowMethods() {
        logMissingShadowMethods = true;
    }

    public void silence() {
        logMissingShadowMethods = false;
    }

    private class InvocationPlan {
        private final Class clazz;
        private final Class shadowClass;
        private final String methodName;
        private final boolean isStatic;
        private final String[] paramTypes;

        private final ClassLoader classLoader;
        private final boolean hasShadowImplementation;
        private Class<?>[] paramClasses;
        private Class<?> declaredShadowClass;
        private Method method;

        public InvocationPlan(InvocationProfile invocationProfile) {
            this(invocationProfile.clazz, invocationProfile.shadowClass,
                    invocationProfile.methodName, invocationProfile.isStatic, invocationProfile.paramTypes);
        }

        public InvocationPlan(Class clazz, Class shadowClass, String methodName, boolean isStatic, String... paramTypes) {
            this.clazz = clazz;
            this.shadowClass = shadowClass;
            this.methodName = methodName.equals("<init>")
                    ? InstrumentingClassLoader.CONSTRUCTOR_METHOD_NAME
                    : methodName;
            this.isStatic = isStatic;
            this.paramTypes = paramTypes;

            this.classLoader = clazz.getClassLoader();
            this.hasShadowImplementation = prepare();
        }

        public boolean hasShadowImplementation() {
            return hasShadowImplementation;
        }

        public Class<?> getDeclaredShadowClass() {
            return declaredShadowClass;
        }

        public Method getMethod() {
            return method;
        }

        public boolean isI18nSafe() {
            // method is loaded by another class loader. So do everything reflectively.
            Annotation[] annos = method.getAnnotations();
            for (Annotation anno : annos) {
                String name = anno.annotationType().getName();
                if (name.equals(Implementation.class.getName())) {
                    try {
                        Method m = (anno).getClass().getMethod("i18nSafe");
                        return (Boolean) m.invoke(anno);
                    } catch (Exception e) {
                        return true;    // should probably throw some other exception
                    }
                }
            }

            return true;
        }

        public boolean prepare() {
            paramClasses = getParamClasses();

            Class<?> originalClass = loadClass(clazz.getName(), classLoader);

            declaredShadowClass = findDeclaredShadowClassForMethod(originalClass, methodName, paramClasses);
            if (declaredShadowClass == null) {
                return false;
            }

            if (!isStatic) {
                String directShadowMethodName = RobolectricInternals.directMethodName(declaredShadowClass.getName(), methodName);

                method = getMethod(shadowClass, directShadowMethodName, paramClasses);
                if (method == null) {
                    method = getMethod(shadowClass, methodName, paramClasses);
                }
            } else {
                method = getMethod(findShadowClass(clazz), methodName, paramClasses);
            }

            if (method == null) {
                if (debug) {
                    System.out.println("No method found for " + clazz + "." + methodName + "(" + asList(paramClasses) + ") on " + declaredShadowClass.getName());
                }
                return false;
            }

            if (isStatic != Modifier.isStatic(method.getModifiers())) {
                throw new RuntimeException("method staticness of " + clazz.getName() + "." + methodName + " and " + declaredShadowClass.getName() + "." + method.getName() + " don't match");
            }

            // todo: not this
            if (clazz.getName().startsWith("android.support") && !clazz.getName().equals(LocalBroadcastManager.class.getName())) {
                return false;
            }

            method.setAccessible(true);

            return true;
        }

        private Class<?> findDeclaredShadowClassForMethod(Class<?> originalClass, String methodName, Class<?>[] paramClasses) {
            Class<?> declaringClass = findDeclaringClassForMethod(methodName, paramClasses, originalClass);
            return findShadowClass(declaringClass);
        }

        private Class<?> findShadowClass(Class<?> originalClass) {
            String declaredShadowClassName = getShadowClassName(originalClass);
            if (declaredShadowClassName == null) {
                return null;
            }
            return loadClass(declaredShadowClassName, classLoader);
        }

        private Class<?> findDeclaringClassForMethod(String methodName, Class<?>[] paramClasses, Class<?> originalClass) {
            Class<?> declaringClass;
            if (this.methodName.equals(InstrumentingClassLoader.CONSTRUCTOR_METHOD_NAME)) {
                declaringClass = originalClass;
            } else {
                Method originalMethod;
                try {
                    originalMethod = originalClass.getDeclaredMethod(methodName, paramClasses);
                } catch (NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
                declaringClass = originalMethod.getDeclaringClass();
            }
            return declaringClass;
        }

        private Class<?>[] getParamClasses() {
            Class<?>[] paramClasses = new Class<?>[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                paramClasses[i] = loadClass(paramTypes[i], classLoader);
            }
            return paramClasses;
        }

        private Method getMethod(Class<?> clazz, String methodName, Class<?>[] paramClasses) {
            Method method;
            try {
                method = clazz.getMethod(methodName, paramClasses);
            } catch (NoSuchMethodException e) {
                try {
                    method = clazz.getDeclaredMethod(methodName, paramClasses);
                } catch (NoSuchMethodException e1) {
                    method = null;
                }
            }

            if (method != null && !isOnShadowClass(method)) {
                method = null;
            }

            return method;
        }

        private boolean isOnShadowClass(Method method) {
            Class<?> declaringClass = method.getDeclaringClass();
            // why doesn't getAnnotation(org.robolectric.internal.Implements) work here? It always returns null. pg 20101115
            // It doesn't work because the method and declaringClass were loaded by the delegate class loader. Different classloaders so types don't match. mp 20110823
            for (Annotation annotation : declaringClass.getAnnotations()) { // todo fix
                if (annotation.annotationType().toString().equals("interface org.robolectric.internal.Implements")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "delegating to " + declaredShadowClass.getName() + "." + method.getName()
                    + "(" + Arrays.toString(method.getParameterTypes()) + ")";
        }

        public Object callOriginal(Object instance, Object[] params) throws InvocationTargetException, IllegalAccessException {
            try {
                Method method = clazz.getDeclaredMethod(RobolectricInternals.directMethodName(clazz.getName(), methodName), paramClasses);
                method.setAccessible(true);
                return method.invoke(instance, params);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        public boolean shouldDelegateToRealMethodWhenMethodShadowIsMissing() {
            String className = clazz.getName();
            ShadowConfig shadowConfig = shadowClassMap.get(className);
            int dollarIndex;
            if (shadowConfig == null && (dollarIndex = className.indexOf('$')) > -1) {
                className = className.substring(0, dollarIndex);
                shadowConfig = shadowClassMap.get(className);

                // todo: test
            }
            if (shadowConfig != null && shadowConfig.callThroughByDefault) {
                return true;
            }

            boolean delegateToReal = setup.invokeApiMethodBodiesWhenShadowMethodIsMissing(clazz, methodName, paramClasses);
            if (debug) {
                System.out.println("DEBUG: Shall we invoke real method on " + clazz + "." + methodName + "("
                        + Join.join(", ", paramClasses) + ")? " + (delegateToReal ? "yup!" : "nope!"));
            }
            return delegateToReal;
        }
    }

    private class MetaShadow {
        List<Field> realObjectFields = new ArrayList<Field>();

        public MetaShadow(Class<?> shadowClass) {
            while (shadowClass != null) {
                for (Field field : shadowClass.getDeclaredFields()) {
                    if (field.isAnnotationPresent(RealObject.class)) {
                        field.setAccessible(true);
                        realObjectFields.add(field);
                    }
                }
                shadowClass = shadowClass.getSuperclass();
            }

        }
    }
}
