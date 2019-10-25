/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.util;

import com.zaxxer.hikari.pool.*;
import javassist.*;
import javassist.bytecode.ClassFile;

import java.io.IOException;
import java.lang.reflect.Array;
import java.sql.*;
import java.util.*;

/**
 * This class generates the proxy objects for {@link Connection}, {@link Statement},
 * {@link PreparedStatement}, and {@link CallableStatement}.  Additionally it injects
 * method bodies into the {@link ProxyFactory} class methods that can instantiate
 * instances of the generated proxies.
 *
 * @author Brett Wooldridge
 */
public final class JavassistProxyFactory {

    private static ClassPool classPool;
    private static String genDirectory = "";

    public static void main(String... args) throws Exception {

        classPool = new ClassPool();
        classPool.importPackage("java.sql");
        classPool.appendClassPath(new LoaderClassPath(JavassistProxyFactory.class.getClassLoader()));

        if (args.length > 0) {
            genDirectory = args[0];
        }

        // Cast is not needed for these
        String methodBody = "{ try { return delegate.method($$); } catch (SQLException e) { throw checkException(e); } }";
        generateProxyClass(Connection.class, ProxyConnection.class.getName(), methodBody);
        generateProxyClass(Statement.class, ProxyStatement.class.getName(), methodBody);
        generateProxyClass(ResultSet.class, ProxyResultSet.class.getName(), methodBody);
        generateProxyClass(DatabaseMetaData.class, ProxyDatabaseMetaData.class.getName(), methodBody);

        // For these we have to cast the delegate
        methodBody = "{ try { return ((cast) delegate).method($$); } catch (SQLException e) { throw checkException(e); } }";
        generateProxyClass(PreparedStatement.class, ProxyPreparedStatement.class.getName(), methodBody);
        generateProxyClass(CallableStatement.class, ProxyCallableStatement.class.getName(), methodBody);

        modifyProxyFactory();
    }

    private static void modifyProxyFactory() throws NotFoundException, CannotCompileException, IOException {
        System.out.println("Generating method bodies for com.zaxxer.hikari.proxy.ProxyFactory");

        String packageName = ProxyConnection.class.getPackage().getName();

        // 运行到这里,ProxyFactory 类已经生成了class文件,

        CtClass proxyCt = classPool.getCtClass("com.zaxxer.hikari.pool.ProxyFactory");
        for (CtMethod method : proxyCt.getMethods()) {
            switch (method.getName()) {
                case "getProxyConnection":
                    method.setBody("{return new " + packageName + ".HikariProxyConnection($$);}");
                    break;
                case "getProxyStatement":
                    method.setBody("{return new " + packageName + ".HikariProxyStatement($$);}");
                    break;
                case "getProxyPreparedStatement":
                    method.setBody("{return new " + packageName + ".HikariProxyPreparedStatement($$);}");
                    break;
                case "getProxyCallableStatement":
                    method.setBody("{return new " + packageName + ".HikariProxyCallableStatement($$);}");
                    break;
                case "getProxyResultSet":
                    method.setBody("{return new " + packageName + ".HikariProxyResultSet($$);}");
                    break;
                case "getProxyDatabaseMetaData":
                    method.setBody("{return new " + packageName + ".HikariProxyDatabaseMetaData($$);}");
                    break;
                default:
                    // unhandled method
                    break;
            }
        }

        // 生成新的 ProxyFactory 文件,并替换原来的已经生成的文件
        proxyCt.writeFile(genDirectory + "target/classes");
    }

    /**
     * Generate Javassist Proxy Classes 生成代理类,
     *
     * @param primaryInterface 主要交换器
     * @param superClassName   父类
     * @param methodBody       方法
     */
    private static <T> void generateProxyClass(Class<T> primaryInterface, String superClassName, String methodBody) throws Exception {
        // 生成新的类名
        String newClassName = superClassName.replaceAll("(.+)\\.(\\w+)", "$1.Hikari$2");

        CtClass superCt = classPool.getCtClass(superClassName);
        CtClass targetCt = classPool.makeClass(newClassName, superCt);
        targetCt.setModifiers(Modifier.FINAL);

        System.out.println("Generating " + newClassName);

        targetCt.setModifiers(Modifier.PUBLIC);

        // 所有父类的final方法,不用生成这些
        // Make a set of method signatures we inherit implementation for, so we don't generate delegates for these
        Set<String> superSigs = new HashSet<>();
        for (CtMethod method : superCt.getMethods()) {
            if ((method.getModifiers() & Modifier.FINAL) == Modifier.FINAL) {
                superSigs.add(method.getName() + method.getSignature());
            }
        }

        Set<String> methods = new HashSet<>();
        // 当前类实现的所有接口
        final Set<Class<?>> allInterfaces = getAllInterfaces(primaryInterface);
        for (Class<?> intf : allInterfaces) {
            CtClass intfCt = classPool.getCtClass(intf.getName());
            targetCt.addInterface(intfCt);
            // 添加接口的所有方法
            for (CtMethod intfMethod : intfCt.getDeclaredMethods()) {
                final String signature = intfMethod.getName() + intfMethod.getSignature();

                // don't generate delegates for methods we override
                if (superSigs.contains(signature)) {
                    continue;
                }

                // Ignore already added methods that come from other interfaces
                if (methods.contains(signature)) {
                    continue;
                }

                // Track what methods we've added
                methods.add(signature);

                // Clone the method we want to inject into
                CtMethod method = CtNewMethod.copy(intfMethod, targetCt, null);

                String modifiedBody = methodBody;

                // If the super-Proxy has concrete methods (non-abstract), transform the call into a simple super.method() call
                CtMethod superMethod = superCt.getMethod(intfMethod.getName(), intfMethod.getSignature());
                if ((superMethod.getModifiers() & Modifier.ABSTRACT) != Modifier.ABSTRACT && !isDefaultMethod(intf, intfMethod)) {
                    modifiedBody = modifiedBody.replace("((cast) ", "");
                    modifiedBody = modifiedBody.replace("delegate", "super");
                    modifiedBody = modifiedBody.replace("super)", "super");
                }

                modifiedBody = modifiedBody.replace("cast", primaryInterface.getName());

                // Generate a method that simply invokes the same method on the delegate
                if (isThrowsSqlException(intfMethod)) {
                    modifiedBody = modifiedBody.replace("method", method.getName());
                } else {
                    modifiedBody = "{ return ((cast) delegate).method($$); }".replace("method", method.getName()).replace("cast", primaryInterface.getName());
                }

                if (method.getReturnType() == CtClass.voidType) {
                    modifiedBody = modifiedBody.replace("return", "");
                }

                method.setBody(modifiedBody);
                targetCt.addMethod(method);
            }
        }

        targetCt.getClassFile().setMajorVersion(ClassFile.JAVA_8);
        targetCt.writeFile(genDirectory + "target/classes");
    }

    private static boolean isThrowsSqlException(CtMethod method) {
        try {
            for (CtClass clazz : method.getExceptionTypes()) {
                if ("SQLException".equals(clazz.getSimpleName())) {
                    return true;
                }
            }
        } catch (NotFoundException e) {
            // fall thru
        }

        return false;
    }

    /**
     * 获取默认接口方法
     *
     * @param intf
     * @param intfMethod
     * @return
     * @throws Exception
     */
    private static boolean isDefaultMethod(Class<?> intf, CtMethod intfMethod) throws Exception {

        List<Class<?>> paramTypes = new ArrayList<>();

        // 参数类型
        for (CtClass pt : intfMethod.getParameterTypes()) {
            // 从CtClass 取得参数的 Class 类型
            paramTypes.add(toJavaClass(pt));
        }
        // 考虑到Java1.8以下的原因 Method.isDefault() jdk1.8以上才有的.
        // 不使用: intf.getDeclaredMethod(intfMethod.getName(), paramTypes.toArray(new Class[0])).isDefault()
        return intf.getDeclaredMethod(intfMethod.getName(), paramTypes.toArray(new Class[0])).toString().contains("default ");
    }

    /**
     * 获取实现的所有接口 递归获取
     *
     * @param clazz
     * @return
     */
    private static Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> intf : clazz.getInterfaces()) {
            if (intf.getInterfaces().length > 0) {
                interfaces.addAll(getAllInterfaces(intf));
            }
            interfaces.add(intf);
        }
        if (clazz.getSuperclass() != null) {
            interfaces.addAll(getAllInterfaces(clazz.getSuperclass()));
        }

        if (clazz.isInterface()) {
            interfaces.add(clazz);
        }

        return interfaces;
    }

    private static Class<?> toJavaClass(CtClass cls) throws Exception {
        if (cls.getName().endsWith("[]")) {
            // CtClass.getName() 如下:
            // Integer[] ==> java.lang.Integer[]
            // 在本项目中,最多也就是只有一维数组的情况出现,这里不处理二维数组
            return Array.newInstance(toJavaClass(cls.getName().replace("[]", "")), 0).getClass();
        } else {
            return toJavaClass(cls.getName());
        }
    }

    private static Class<?> toJavaClass(String cn) throws Exception {
        switch (cn) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "short":
                return short.class;
            case "byte":
                return byte.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "boolean":
                return boolean.class;
            case "char":
                return char.class;
            case "void":
                return void.class;
            default:
                return Class.forName(cn);
        }
    }
}
