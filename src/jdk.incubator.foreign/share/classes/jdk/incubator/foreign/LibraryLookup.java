/*
 *  Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */
package jdk.incubator.foreign;

import jdk.internal.foreign.LibrariesHelper;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;

import java.io.File;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A native library lookup. Exposes a lookup operation for searching symbols, see {@link LibraryLookup#lookup(String)}.
 * A given native library remains loaded as long as there is at least one <em>live</em> library lookup instance referring
 * to it.
 * All instances generated by a given library lookup object contain a strong reference to said lookup object,
 * therefore preventing library unloading. For {@linkplain #lookup(String, MemoryLayout) memory segments} obtained from a library lookup object,
 * this means that clients can safely dereference memory associated with lookup symbols, as follows:
 * <pre>{@code
 * LibraryLookup defaultLookup = LibraryLookup.ofDefault();
 * MemorySegment errnoSegment = defaultLookup.lookup("errno", MemoryLayouts.JAVA_INT).get();
 * int errno = MemoryAccess.getInt(errnoSegment);
 * }</pre>
 * <p>
 * For {@linkplain #lookup(String) memory addresses} obtained from a library lookup object,
 * since {@linkplain CLinker#downcallHandle(Addressable, MethodType, FunctionDescriptor) native method handles}
 * also maintain a strong reference to the addressable parameter used for their construction, there is
 * always a strong reachability chain from a native method handle to a lookup object (the one that was used to lookup
 * the native library symbol the method handle refers to). This is useful to prevent situations where a native library
 * is unloaded in the middle of a native call.
 * <p>
 * To allow for a library to be unloaded, a client will have to discard any strong references it
 * maintains, directly, or indirectly to a lookup object associated with given library.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument, or an array argument containing one or more {@code null}
 * elements to a method in this class causes a {@link NullPointerException NullPointerException} to be thrown. </p>
 */
public interface LibraryLookup {

    /**
     * Looks up a symbol with given name in this library. The returned memory address maintains a strong reference to this lookup object.
     *
     * @param name the symbol name.
     * @return the memory address associated with the library symbol (if any).
     */
    Optional<MemoryAddress> lookup(String name);

    /**
     * Looks up a symbol with given name in this library. The returned memory segment has a size that matches that of
     * the specified layout, and maintains a strong reference to this lookup object. This method can be useful
     * to lookup global variable symbols in a foreign library.
     *
     * @param name the symbol name.
     * @param layout the layout to be associated with the library symbol.
     * @return the memory segment associated with the library symbol (if any).
     * @throws IllegalArgumentException if the address associated with the lookup symbol do not match the
     * {@linkplain MemoryLayout#byteAlignment() alignment constraints} in {@code layout}.
     */
    Optional<MemorySegment> lookup(String name, MemoryLayout layout);

    /**
     * Obtain a default library lookup object.
     * <p>
     * This method is <a href="package-summary.html#restricted"><em>restricted</em></a>.
     * Restricted method are unsafe, and, if used incorrectly, their use might crash
     * the JVM or, worse, silently result in memory corruption. Thus, clients should refrain from depending on
     * restricted methods, and use safe and supported functionalities, where possible.
     *
     * @return the default library lookup object.
     * @throws IllegalCallerException if access to this method occurs from a module {@code M} and the command line option
     * {@code --enable-native-access} is either absent, or does not mention the module name {@code M}, or
     * {@code ALL-UNNAMED} in case {@code M} is an unnamed module.
     */
    @CallerSensitive
    static LibraryLookup ofDefault() {
        Reflection.ensureNativeAccess(Reflection.getCallerClass());
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(new RuntimePermission("java.foreign.getDefaultLibrary"));
        }
        return LibrariesHelper.getDefaultLibrary();
    }

    /**
     * Obtain a library lookup object corresponding to a library identified by given path.
     * <p>
     * This method is <a href="package-summary.html#restricted"><em>restricted</em></a>.
     * Restricted method are unsafe, and, if used incorrectly, their use might crash
     * the JVM or, worse, silently result in memory corruption. Thus, clients should refrain from depending on
     * restricted methods, and use safe and supported functionalities, where possible.
     *
     * @param path the library absolute path.
     * @return a library lookup object for given path.
     * @throws IllegalArgumentException if the specified path does not correspond to an absolute path,
     * e.g. if {@code !path.isAbsolute()}.
     * @throws IllegalCallerException if access to this method occurs from a module {@code M} and the command line option
     * {@code --enable-native-access} is either absent, or does not mention the module name {@code M}, or
     * {@code ALL-UNNAMED} in case {@code M} is an unnamed module.
     */
    @CallerSensitive
    static LibraryLookup ofPath(Path path) {
        Reflection.ensureNativeAccess(Reflection.getCallerClass());
        Objects.requireNonNull(path);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Not an absolute path: " + path.toString());
        }
        String absolutePath = path.toString();
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkLink(absolutePath);
        }
        return LibrariesHelper.load(absolutePath);
    }

    /**
     * Obtain a library lookup object corresponding to a library identified by given library name. The library name
     * is decorated according to the platform conventions (e.g. on Linux, the {@code lib} prefix is added,
     * as well as the {@code .so} extension); the resulting name is then looked up in the standard native
     * library path (which can be overriden, by setting the <code>java.library.path</code> property).
     * <p>
     * This method is <a href="package-summary.html#restricted"><em>restricted</em></a>.
     * Restricted method are unsafe, and, if used incorrectly, their use might crash
     * the JVM or, worse, silently result in memory corruption. Thus, clients should refrain from depending on
     * restricted methods, and use safe and supported functionalities, where possible.
     *
     * @param libName the library name.
     * @return a library lookup object for given library name.
     * @throws IllegalCallerException if access to this method occurs from a module {@code M} and the command line option
     * {@code --enable-native-access} is either absent, or does not mention the module name {@code M}, or
     * {@code ALL-UNNAMED} in case {@code M} is an unnamed module.
     */
    @CallerSensitive
    static LibraryLookup ofLibrary(String libName) {
        Reflection.ensureNativeAccess(Reflection.getCallerClass());
        Objects.requireNonNull(libName);
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkLink(libName);
        }
        if (libName.indexOf(File.separatorChar) != -1) {
            throw new UnsatisfiedLinkError(
                    "Directory separator should not appear in library name: " + libName);
        }
        return LibrariesHelper.loadLibrary(libName);
    }
}
