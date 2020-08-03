/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.symbols.table.internal;

import static net.sourceforge.pmd.lang.java.symbols.table.internal.SuperTypesEnumerator.DIRECT_STRICT_SUPERTYPES;
import static net.sourceforge.pmd.util.CollectionUtil.listOfNotNull;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.Pair;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.pcollections.HashTreePSet;
import org.pcollections.PSet;

import net.sourceforge.pmd.lang.java.symbols.JAccessibleElementSymbol;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFieldSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.symbols.SymbolResolver;
import net.sourceforge.pmd.lang.java.symbols.table.ScopeInfo;
import net.sourceforge.pmd.lang.java.symbols.table.coreimpl.CoreResolvers;
import net.sourceforge.pmd.lang.java.symbols.table.coreimpl.NameResolver;
import net.sourceforge.pmd.lang.java.symbols.table.coreimpl.NameResolver.SingleNameResolver;
import net.sourceforge.pmd.lang.java.symbols.table.coreimpl.ShadowChainBuilder;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.JVariableSig;
import net.sourceforge.pmd.lang.java.types.JVariableSig.FieldSig;
import net.sourceforge.pmd.lang.java.types.TypeOps;
import net.sourceforge.pmd.lang.java.types.internal.infer.OverloadComparator;
import net.sourceforge.pmd.util.CollectionUtil;

public final class JavaResolvers {

    private JavaResolvers() {
        // utility class
    }

    /** Prepend the package name, handling empty package. */
    static String prependPackageName(String pack, String name) {
        return pack.isEmpty() ? name : pack + "." + name;
    }

    /**
     * Returns true if the given element can be imported in the current file
     * (it's visible & accessible). This is not a general purpose accessibility
     * check and is only appropriate for imports.
     *
     *
     * <p>We consider protected members inaccessible outside of the package they were declared in,
     * which is an approximation but won't cause problems in practice.
     * In an ACU in another package, the name is accessible only inside classes that inherit
     * from the declaring class. But inheriting from a class makes its static members
     * accessible via simple name too. So this will actually be picked up by some other symbol table
     * when in the subclass. Usages outside of the subclass would have made the compilation fail.
     */
    static boolean canBeImportedIn(String thisPackage, JAccessibleElementSymbol member) {
        int modifiers = member.getModifiers();
        if (Modifier.isPublic(modifiers)) {
            return true;
        } else if (Modifier.isPrivate(modifiers)) {
            return false;
        } else {
            // then it's package private, or protected
            return thisPackage.equals(member.getPackageName());
        }
    }

    @NonNull
    static NameResolver<JTypeMirror> importedOnDemand(Set<String> lazyImportedPackagesAndTypes,
                                                      final SymbolResolver symResolver,
                                                      final String thisPackage) {
        return new SingleNameResolver<JTypeMirror>() {
            @Nullable
            @Override
            public JTypeMirror resolveFirst(String simpleName) {
                for (String pack : lazyImportedPackagesAndTypes) {
                    // here 'pack' may be a package or a type name, so we must resolve by canonical name
                    String name = prependPackageName(pack, simpleName);
                    JClassSymbol sym = symResolver.resolveClassFromCanonicalName(name);
                    if (sym != null && canBeImportedIn(thisPackage, sym)) {
                        return sym.getTypeSystem().typeOf(sym, false);
                    }
                }
                return null;
            }

            @Override
            public String toString() {
                return "ImportOnDemandResolver(" + lazyImportedPackagesAndTypes + ")";
            }
        };
    }

    @NonNull
    static NameResolver<JTypeMirror> packageResolver(SymbolResolver symResolver, String packageName) {
        return new SingleNameResolver<JTypeMirror>() {
            @Nullable
            @Override
            public JTypeMirror resolveFirst(String simpleName) {
                JClassSymbol sym = symResolver.resolveClassFromBinaryName(prependPackageName(packageName, simpleName));
                if (sym != null) {
                    return sym.getTypeSystem().typeOf(sym, false);
                }
                return null;
            }

            @Override
            public String toString() {
                return "PackageResolver(" + packageName + ")";
            }
        };
    }

    /** All methods in a type, taking care of hiding/overriding. */
    static NameResolver<JMethodSig> subtypeMethodResolver(JClassType t) {
        JClassSymbol nestRoot = t.getSymbol().getNestRoot();
        return new NameResolver<JMethodSig>() {
            @Override
            public @NonNull List<JMethodSig> resolveHere(String simpleName) {
                return t.streamMethods(
                    it -> it.getSimpleName().equals(simpleName)
                        && isAccessibleIn(nestRoot, it, true) // fetch protected methods
                ).collect(OverloadComparator.collectMostSpecific(t)); // remove overridden, hidden methods
            }

            @Override
            public String toString() {
                return "methods of " + t;
            }
        };
    }

    /** Static methods with a given name. */
    static NameResolver<JMethodSig> staticImportMethodResolver(JClassType container, @NonNull String accessPackageName, String simpleName) {
        assert simpleName != null;
        assert accessPackageName != null;
        return new NameResolver<JMethodSig>() {
            @Override
            public @NonNull List<JMethodSig> resolveHere(String simpleName) {
                return container.streamMethods(
                    it -> Modifier.isStatic(it.getModifiers())
                        && it.getSimpleName().equals(simpleName)
                        // Technically, importing a static protected method may be valid
                        // inside some of the classes in the compilation unit. This test
                        // makes it not in scope in those classes. But it's also visible
                        // from the subclass as an "inherited" member, so is in scope in
                        // the relevant contexts.
                        && isAccessibleIn(null, accessPackageName, it, false)
                ).collect(OverloadComparator.collectMostSpecific(container)); // remove overridden, hidden methods
            }

            @Override
            public String toString() {
                return "static methods w/ name " + simpleName + " of " + container;
            }
        };
    }

    static BinaryOperator<List<JMethodSig>> methodMerger() {
        return (myResult, otherResult) -> {
            if (otherResult.isEmpty()) {
                return myResult;
            }

            // For both the input lists, their elements are pairwise non-equivalent.
            // If any element of myResult is override-equivalent to
            // another in otherResult, then we must exclude the otherResult

            BitSet isShadowed = new BitSet(otherResult.size());

            for (JMethodSig m1 : myResult) {
                int i = 0;
                for (JMethodSig m2 : otherResult) {
                    boolean isAlreadyShadowed = isShadowed.get(i);
                    if (!isAlreadyShadowed && TypeOps.areOverrideEquivalent(m1, m2)) {
                        isShadowed.set(i); // we'll remove it later
                    }
                    i++;
                }
            }

            if (isShadowed.isEmpty()) {
                return CollectionUtil.concatView(myResult, otherResult);
            } else {
                List<JMethodSig> result = new ArrayList<>(myResult.size() + otherResult.size() - 1);
                result.addAll(myResult);

                int last = 0;
                for (int i = isShadowed.nextSetBit(0); i >= 0; i = isShadowed.nextSetBit(i + 1)) {
                    result.addAll(otherResult.subList(last, i));
                    last = i + 1;
                }
                if (last != otherResult.size()) {
                    result.addAll(otherResult.subList(last, otherResult.size()));
                }
                return Collections.unmodifiableList(result);
            }
        };
    }


    /**
     * Resolvers for inherited member types and fields. We can't process
     * methods that way, because there may be duplicates and the equals
     * of {@link JMethodSymbol} is not reliable for now (cannot differentiate
     * overloads). But also, usually a subset of methods is used in a subclass,
     * and it's ok performance-wise to process them on-demand.
     */
    static Pair<NameResolver<JTypeMirror>, NameResolver<JVariableSig>> inheritedMembersResolvers(JClassType t) {
        JClassSymbol nestRoot = t.getSymbol().getNestRoot();

        ShadowChainBuilder<JVariableSig, ScopeInfo>.ResolverBuilder fields = SymTableFactory.VARS.new ResolverBuilder();
        ShadowChainBuilder<JTypeMirror, ScopeInfo>.ResolverBuilder types = SymTableFactory.TYPES.new ResolverBuilder();

        Predicate<JVariableSig> isFieldAccessible = s -> isAccessibleIn(nestRoot, (JFieldSymbol) s.getSymbol(), true);
        Predicate<JClassType> isTypeAccessible = s -> isAccessibleIn(nestRoot, s.getSymbol(), true);

        for (JClassType next : DIRECT_STRICT_SUPERTYPES.iterable(t)) {
            walkSelf(next, isFieldAccessible, isTypeAccessible, fields, types, HashTreePSet.empty(), HashTreePSet.empty());
        }

        // Note that if T is an interface, Object won't have been visited
        // This is fine for now because Object has no fields or nested types
        // in any known version of the JDK

        return Pair.of(types.build(), fields.build());
    }

    private static void walkSelf(JClassType t,
                                 Predicate<? super JVariableSig> isFieldAccessible,
                                 Predicate<? super JClassType> isTypeAccessible,
                                 ShadowChainBuilder<JVariableSig, ?>.ResolverBuilder fields,
                                 ShadowChainBuilder<JTypeMirror, ?>.ResolverBuilder types,
                                 // persistent because may change in every path of the recursion
                                 final PSet<String> hiddenFields,
                                 final PSet<String> hiddenTypes) {

        // Note that it is possible that this process recurses several
        // times into the same interface (if it is reachable from several paths)
        // This is because the set of hidden declarations depends on the
        // full path, and may be different each time.
        // Profiling shows that this doesn't occur very often, and adding
        // a recursion guard is counter-productive performance-wise

        PSet<String> hiddenTypesInSup = processDeclarations(types, hiddenTypes, isTypeAccessible, t.getDeclaredClasses());
        PSet<String> hiddenFieldsInSup = processDeclarations(fields, hiddenFields, isFieldAccessible, t.getDeclaredFields());

        // depth first
        for (JClassType next : DIRECT_STRICT_SUPERTYPES.iterable(t)) {
            walkSelf(next, isFieldAccessible, isTypeAccessible, fields, types, hiddenFieldsInSup, hiddenTypesInSup);
        }
    }

    private static <S> PSet<String> processDeclarations(
        ShadowChainBuilder<? super S, ?>.ResolverBuilder builder,
        PSet<String> hidden,
        Predicate<? super S> isAccessible,
        List<? extends S> syms
    ) {
        for (S inner : syms) {
            String simpleName = builder.getSimpleName(inner);
            if (hidden.contains(simpleName)) {
                continue;
            }

            hidden = hidden.plus(simpleName);

            if (isAccessible.test(inner)) {
                builder.appendWithoutDuplicate(inner);
            }
        }
        return hidden;
    }

    private static boolean isAccessibleIn(@NonNull JClassSymbol nestRoot,
                                          JAccessibleElementSymbol sym,
                                          boolean isOwnerASupertypeOfContext) {
        return isAccessibleIn(nestRoot, nestRoot.getPackageName(), sym, isOwnerASupertypeOfContext);
    }

    /**
     * Whether the given sym is accessible in some type T, given
     * the 'nestRoot' of T, and whether T is a subtype of the class
     * declaring 'sym'. This is a general purpose accessibility check,
     * albeit a bit low-level (but only needs subtyping to be computed once).
     */
    private static boolean isAccessibleIn(@Nullable JClassSymbol nestRoot,
                                          String packageName,
                                          JAccessibleElementSymbol sym,
                                          boolean isOwnerASupertypeOfContext) {
        int modifiers = sym.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);

        switch (modifiers) {
        case Modifier.PUBLIC:
            return true;
        case Modifier.PRIVATE:
            return nestRoot != null && nestRoot.equals(sym.getEnclosingClass().getNestRoot());
        case Modifier.PROTECTED:
            if (isOwnerASupertypeOfContext) {
                return true;
            }
            // fallthrough
        case 0:
            return sym.getPackageName().equals(packageName);
        default:
            throw new AssertionError(Modifier.toString(sym.getModifiers()));
        }
    }


    /**
     * Produce a name resolver that resolves member classes with the
     * given name declared or inherited by the given type. Each access
     * may perform a hierarchy traversal, but this handles hidden and
     * ambiguous declarations nicely.
     *
     * @param c      Class to search
     * @param access Context of where the declaration is referenced
     * @param name   Name of the class to find
     */
    public static NameResolver<JClassType> getMemberClassResolver(JClassType c, @NonNull String accessPackageName, @Nullable JClassSymbol access, String name) {
        return getNamedMemberResolver(c, access, accessPackageName, JClassType::getDeclaredClass, name, JClassType::getSymbol, SymTableFactory.TYPES);
    }

    public static NameResolver<FieldSig> getMemberFieldResolver(JClassType c, @NonNull String accessPackageName, @Nullable JClassSymbol access, String name) {
        return getNamedMemberResolver(c, access, accessPackageName, JClassType::getDeclaredField, name, FieldSig::getSymbol, SymTableFactory.VARS);
    }

    private static <S> NameResolver<S> getNamedMemberResolver(JClassType c,
                                                              @Nullable JClassSymbol access,
                                                              @NonNull String accessPackageName,
                                                              BiFunction<? super JClassType, String, ? extends S> getter,
                                                              String name,
                                                              Function<? super S, ? extends JAccessibleElementSymbol> symbolGetter,
                                                              ShadowChainBuilder<? super S, ?> classes) {
        S found = getter.apply(c, name);
        if (found != null) {
            // fast path, doesn't need to check accessibility, etc
            return CoreResolvers.singleton(name, found);
        }

        JClassSymbol nestRoot = access == null ? null : access.getNestRoot();
        Predicate<S> isAccessible = s -> {
            JAccessibleElementSymbol sym = symbolGetter.apply(s);
            return isAccessibleIn(nestRoot, accessPackageName, sym, isSubtype(access, sym.getEnclosingClass()));
        };

        @SuppressWarnings("unchecked")
        ShadowChainBuilder<S, ?>.ResolverBuilder builder = (ShadowChainBuilder<S, ?>.ResolverBuilder) classes.new ResolverBuilder();

        for (JClassType next : DIRECT_STRICT_SUPERTYPES.iterable(c)) {
            walkForSingleName(next, isAccessible, name, getter, builder, HashTreePSet.empty());
        }

        return builder.build();
    }

    private static boolean isSubtype(JClassSymbol sub, JClassSymbol sup) {
        return sub != null && sub.getTypeSystem().typeOf(sub, true).getAsSuper(sup) != null;
    }

    private static <S> void walkForSingleName(JClassType t,
                                              Predicate<? super S> isAccessible,
                                              String name,
                                              BiFunction<? super JClassType, String, ? extends S> getter,
                                              ShadowChainBuilder<? super S, ?>.ResolverBuilder builder,
                                              final PSet<String> hidden) {

        PSet<String> hiddenInSup = processDeclarations(builder, hidden, isAccessible, listOfNotNull(getter.apply(t, name)));

        if (!hiddenInSup.isEmpty()) {
            // found it in this branch
            // in this method the hidden set is either empty or one element only
            return;
        }

        // depth first
        for (JClassType next : DIRECT_STRICT_SUPERTYPES.iterable(t)) {
            walkForSingleName(next, isAccessible, name, getter, builder, hiddenInSup);
        }
    }

}
