// Copyright (c) 2015 K Team. All Rights Reserved.
package org.kframework.parser.concrete2kore.generator;

import org.apache.commons.collections4.trie.PatriciaTrie;
import org.kframework.attributes.Att;
import org.kframework.builtin.Sorts;
import org.kframework.compile.ConfigurationInfo;
import org.kframework.compile.ConfigurationInfoFromModule;
import org.kframework.definition.Definition;
import org.kframework.definition.DefinitionTransformer;
import org.kframework.definition.Module;
import org.kframework.definition.ModuleTransformer;
import org.kframework.definition.Production;
import org.kframework.definition.ProductionItem;
import org.kframework.definition.RegexTerminal;
import org.kframework.definition.Sentence;
import org.kframework.definition.Terminal;
import org.kframework.kil.Attribute;
import org.kframework.kil.loader.Constants;
import org.kframework.kore.Sort;
import org.kframework.kore.compile.ConvertDataStructureToLookup;
import org.kframework.kore.convertors.KOREtoKIL;
import org.kframework.parser.concrete2kore.ParseInModule;
import scala.collection.Seq;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.kframework.Collections.*;
import static org.kframework.definition.Constructors.Att;
import static org.kframework.definition.Constructors.*;
import static org.kframework.kore.KORE.*;
import static scala.compat.java8.JFunction.func;

/**
 * Generator for rule and ground parsers.
 * Takes as input a reference to a definition containing all the base syntax of K
 * and uses it to generate a grammar by connecting all users sorts in a lattice with
 * the top sort KItem#Top and the bottom sort KItem#Bottom.
 * <p>
 * The instances of the non-terminal KItem is renamed in KItem#Top if found in the right
 * hand side of a production, and into KItem#Bottom if found in the left hand side.
 */
public class RuleGrammarGenerator {

    private final Definition baseK;
    private final boolean strict;
    private static final Set<Sort> kSorts = new HashSet<>();

    static {
        kSorts.add(Sorts.KBott());
        kSorts.add(Sorts.K());
        kSorts.add(Sorts.KLabel());
        kSorts.add(Sorts.KList());
        kSorts.add(Sorts.KItem());
        kSorts.add(Sort("RuleContent"));
        kSorts.add(Sort("KConfigVar"));
        kSorts.add(Sorts.KString());
    }

    private static Set<Sort> kSorts() {
        return java.util.Collections.unmodifiableSet(kSorts);
    }

    /// modules that have a meaning:
    public static final String RULE_CELLS = "RULE-CELLS";
    public static final String CONFIG_CELLS = "CONFIG-CELLS";
    public static final String K = "K";
    public static final String AUTO_CASTS = "AUTO-CASTS";
    public static final String K_TOP_SORT = "K-TOP-SORT";
    public static final String K_BOTTOM_SORT = "K-BOTTOM-SORT";
    public static final String AUTO_FOLLOW = "AUTO-FOLLOW";
    public static final String PROGRAM_LISTS = "PROGRAM-LISTS";
    public static final String RULE_LISTS = "RULE-LISTS";
    public static final String BASIC_K = "BASIC-K";

    /**
     * Initialize a grammar generator.
     *
     * @param baseK  A Definition containing a K module giving the syntax of K itself.
     *               The default K syntax is defined in include/kast.k.
     * @param strict true if the generated parsers should retain inferred variable
     *               sorts as sort predicate in the requires clause.
     */
    public RuleGrammarGenerator(Definition baseK, boolean strict) {
        this.baseK = DefinitionTransformer.from((Module m) -> {
            if (!m.name().equals(BASIC_K))
                return m;
            Set<Sentence> castProds = new HashSet<>();
            castProds.addAll(makeCasts(Sorts.KLabel(), Sorts.KLabel(), Sorts.KLabel()));
            castProds.addAll(makeCasts(Sorts.KList(), Sorts.KList(), Sorts.KList()));
            castProds.addAll(makeCasts(Sorts.KBott(), Sorts.K(), Sorts.KItem()));
            castProds.addAll(makeCasts(Sorts.KBott(), Sorts.K(), Sorts.K()));
            return Module(m.name(),
                    m.imports(),
                    org.kframework.Collections.addAll(m.localSentences(), immutable(castProds)),
                    m.att());
        }, "Generate cast prods for " + BASIC_K).apply(baseK);
        this.strict = strict;
    }

    private Set<Module> renameKItem2Bottom(Set<Module> def) {
        // TODO: do renaming of KItem and K in the LHS to KBott?
        return def;
    }

    /**
     * Creates the seed module that can be used to parse rules.
     * Imports module markers RULE-CELLS and K found in /include/kast.k.
     *
     * @param mod The user defined module from which to start.
     * @return a new module which imports the original user module and a set of marker modules.
     */
    public Module getRuleGrammar(Module mod) {
        // import RULE-CELLS in order to parse cells specific to rules
        Module newM = new Module(mod.name() + "-" + RULE_CELLS, Set(mod, baseK.getModule(RULE_CELLS).get()), Set(), Att());
        return newM;
    }

    /**
     * Creates the seed module that can be used to parse configurations.
     * Imports module markers CONFIG-CELLS and K found in /include/kast.k.
     *
     * @param mod The user defined module from which to start.
     * @return a new module which imports the original user module and a set of marker modules.
     */
    public Module getConfigGrammar(Module mod) {
        // import CONFIG-CELLS in order to parse cells specific to configurations
        Module newM = new Module(mod.name() + "-" + CONFIG_CELLS, Set(mod, baseK.getModule(CONFIG_CELLS).get()), Set(), Att());
        return newM;
    }

    /**
     * Creates the seed module that can be used to parse programs.
     * Imports module markers PROGRAM-LISTS found in /include/kast.k.
     *
     * @param mod The user defined module from which to start.
     * @return a new module which imports the original user module and a set of marker modules.
     */
    public Module getProgramsGrammar(Module mod) {
        // import PROGRAM-LISTS so user lists are modified to parse programs
        Module newM = new Module(mod.name() + "-PROGRAM-LISTS", Set(mod, baseK.getModule(PROGRAM_LISTS).get()), Set(), Att());
        return newM;
    }

    public static boolean isParserSort(Sort s) {
        return kSorts.contains(s) || s.name().startsWith("#");
    }

    /**
     * Create the rule parser for the given module.
     * It creates a module which includes the given module and the base K module given to the
     * constructor. The new module contains syntax declaration for Casts and the diamond
     * which connects the user concrete syntax with K syntax.
     *
     * @param seedMod module for which to create the parser.
     * @return parser which applies disambiguation filters by default.
     */
    public ParseInModule getCombinedGrammar(Module seedMod) {
        Set<Sentence> prods = new HashSet<>();
    // TODO (radum) delete following paragraph
        //DefinitionTransformer genExtension = DefinitionTransformer.fromSentenceTransformer(func((m, s) ->
        //        new ConvertDataStructureToLookup(m, false).convert(s)), "Generate Extension parser");
        Module mt1 = ModuleTransformer.from((Module m) -> m, "Generate Extension parser").apply(seedMod);
        /*Module mt2 = ModuleTransformer.from(new Function<Module, Module> {
            @Override
            public Object apply(Object o) {
                return null;
            }
        },"Generate Extension parser").apply(seedMod);*/
        Module mt3 = ModuleTransformer.from((Module m) -> {
            return m;
        }, "Generate Extension parser").apply(seedMod);


        /** Extension module is used by the compiler to get information about subsorts and access the definition of casts */
        Module extensionM = ModuleTransformer.from((Module m) -> {
            Set<Sentence> newProds = new HashSet<>();
            if (baseK.getModule(AUTO_CASTS).isDefined() && seedMod.importedModules().contains(baseK.getModule(AUTO_CASTS).get())) { // create the casts
                for (Sort srt : iterable(seedMod.localSorts())) {
                    if (!isParserSort(srt)) {
                        // K ::= K "::Sort" | K ":Sort" | K "<:Sort" | K ":>Sort"
                        newProds.addAll(makeCasts(Sorts.KBott(), Sorts.K(), srt));
                    }
                }
            }
            if (baseK.getModule(K_TOP_SORT).isDefined() && seedMod.importedModules().contains(baseK.getModule(K_TOP_SORT).get())) { // create the upper diamond
                for (Sort srt : iterable(seedMod.localSorts())) {
                    if (!isParserSort(srt)) {
                        // K ::= Sort
                        newProds.add(Production(Sorts.K(), Seq(NonTerminal(srt)), Att()));
                    }
                }
            }
            if (baseK.getModule(K_BOTTOM_SORT).isDefined() && seedMod.importedModules().contains(baseK.getModule(K_BOTTOM_SORT).get())) { // create the lower diamond
                for (Sort srt : iterable(seedMod.localSorts())) {
                    if (!isParserSort(srt)) {
                        // Sort ::= KBott
                        newProds.add(Production(srt, Seq(NonTerminal(Sorts.KBott())), Att()));
                    }
                }
            }
            if (newProds.isEmpty())
                return m;
            return Module(m.name(),
                    org.kframework.Collections.add(baseK.getModule(K).get(), m.imports()),
                    org.kframework.Collections.addAll(m.localSentences(), immutable(newProds)),
                    m.att());
        }, "Generate Extension parser").apply(seedMod);

        // prepare for auto follow restrictions, which needs to be done globally (if necessary)
        Object PRESENT = new Object();
        PatriciaTrie<Object> terminals = new PatriciaTrie<>(); // collect all terminals so we can do automatic follow restriction for prefix terminals
        mutable(extensionM.productions()).stream().forEach(p -> stream(p.items()).forEach(i -> {
            if (i instanceof Terminal) terminals.put(((Terminal) i).value(), PRESENT);
        }));

        // make sure a configuration actually exists, otherwise ConfigurationInfoFromModule explodes.
        final ConfigurationInfo cfgInfo = extensionM.localSentences().exists(func(p -> p instanceof Production && ((Production) p).att().contains("cell")))
        ? new ConfigurationInfoFromModule(extensionM)
        : null;

        /** Disambiguation module is used by the parser to have an easier way of disambiguating parse trees. */
        Module disambM = ModuleTransformer.from((Module m) -> {
            boolean addRuleCells;
            Set<Sentence> newProds;
            if (baseK.getModule(RULE_CELLS).isDefined() && seedMod.importedModules().contains(baseK.getModule(RULE_CELLS).get()) &&
                    cfgInfo != null) { // prepare cell productions for rule parsing
                    newProds = stream(m.localSentences()).flatMap(s -> {
                        if (s instanceof Production && (s.att().contains("cell"))) {
                            Production p = (Production) s;
                            // assuming that productions tagged with 'cell' start and end with terminals, and only have non-terminals in the middle
                            assert p.items().head() instanceof Terminal || p.items().head() instanceof RegexTerminal;
                            assert p.items().last() instanceof Terminal || p.items().last() instanceof RegexTerminal;
                            final ProductionItem body;
                            if (cfgInfo.isLeafCell(p.sort())) {
                                body = p.items().tail().head();
                            } else {
                                body = NonTerminal(Sort("Bag"));
                            }
                            final ProductionItem optDots = NonTerminal(Sort("#OptionalDots"));
                            Seq<ProductionItem> pi = Seq(p.items().head(), optDots, body, optDots, p.items().last());
                            Production p1 = Production(p.klabel().get().name(), Sort("Cell"), pi, p.att());
                            Production p2 = Production(Sort("Cell"), Seq(NonTerminal(p.sort())));
                            return Stream.of(p1, p2);
                        }
                        if (s instanceof Production && (s.att().contains("cellFragment"))) {
                            Production p = (Production) s;
                            Production p1 = Production(Sort("Cell"), Seq(NonTerminal(p.sort())));
                            return Stream.of(p, p1);
                        }
                        return Stream.of(s);
                    }).collect(Collectors.toSet());
            } else {
                newProds = stream(m.localSentences()).collect(Collectors.toSet());
            }

            if (baseK.getModule(AUTO_FOLLOW).isDefined() && seedMod.importedModules().contains(baseK.getModule(AUTO_FOLLOW).get())) {
                newProds = newProds.stream().map(s -> {
                    if (s instanceof Production) {
                        Production p = (Production) s;
                        if (p.sort().name().startsWith("#"))
                            return p; // don't do anything for such productions since they are advanced features
                        // rewrite productions to contain follow restrictions for prefix terminals
                        // example _==_ and _==K_ can produce ambiguities. Rewrite the first into _(==(?![K])_
                        // this also takes care of casting and productions that have ":"
                        Seq<ProductionItem> items = map(pi -> {
                            if (pi instanceof Terminal) {
                                Terminal t = (Terminal) pi;
                                if (t.value().trim().equals(""))
                                    return pi;
                                Set<String> follow = new HashSet<>();
                                terminals.prefixMap(t.value()).keySet().stream().filter(biggerString -> !t.value().equals(biggerString))
                                        .forEach(biggerString -> {
                                            String ending = biggerString.substring(t.value().length());
                                            follow.add(ending);
                                        });
                                // add follow restrictions for the characters that might produce ambiguities
                                if (!follow.isEmpty()) {
                                    return Terminal.apply(t.value(), follow.stream().collect(toList()));
                                }
                            }
                            return pi;
                        }, p.items());
                        if (p.klabel().isDefined())
                            p = Production(p.klabel().get().name(), p.sort(), Seq(items), p.att());
                        else
                            p = Production(p.sort(), Seq(items), p.att());
                        return p;
                    }
                    return s;
                }).collect(Collectors.toSet());
            }

            if (baseK.getModule(RULE_LISTS).isDefined() && seedMod.importedModules().contains(baseK.getModule(RULE_LISTS).get())) {
                for (UserList ul : UserList.getLists(newProds)) {
                    org.kframework.definition.Production prod1;
                    // Es ::= E
                    prod1 = Production(ul.sort, Seq(NonTerminal(ul.childSort)));
                    newProds.add(prod1);
                }
            }

            return Module(m.name(), m.imports(), immutable(newProds), m.att());
        }, "Generate Disambiguation parser").apply(seedMod);

        /** Parsing module is used to generate the grammar for the kernel of the parser. */
        Module parseM = ModuleTransformer.from((Module m) -> {
            if (baseK.getModule(PROGRAM_LISTS).isDefined() && seedMod.importedModules().contains(baseK.getModule(PROGRAM_LISTS).get())) {
                Set<Sentence> newProds = mutable(m.localSentences());
                // if no start symbol has been defined in the configuration, then use K
                for (Sort srt : iterable(m.localSorts())) {
                    if (!kSorts.contains(srt) && !seedMod.listSorts().contains(srt)) {
                        // K ::= Sort
                        newProds.add(Production(Sorts.K(), Seq(NonTerminal(srt)), Att()));
                    }
                }
                // for each triple, generate a new pattern which works better for parsing lists in programs.
                for (UserList ul : UserList.getLists(newProds)) {
                    org.kframework.definition.Production prod1, prod2, prod3, prod4, prod5;
                    // Es#Terminator ::= "" [klabel('.Es)]
                    prod1 = Production(ul.terminatorKLabel, Sort(ul.sort + "#Terminator"), Seq(Terminal("")),
                            ul.attrs.add("klabel", ul.terminatorKLabel).add(Constants.ORIGINAL_PRD, ul.pTerminator));
                    // Ne#Es ::= E "," Ne#Es [klabel('_,_)]
                    prod2 = Production(ul.klabel, Sort("Ne#" + ul.sort),
                            Seq(NonTerminal(ul.childSort), Terminal(ul.separator), NonTerminal(Sort("Ne#" + ul.sort))),
                            ul.attrs.add("klabel", ul.klabel).add(Constants.ORIGINAL_PRD, ul.pList));
                    // Ne#Es ::= E Es#Terminator [klabel('_,_)]
                    prod3 = Production(ul.klabel, Sort("Ne#" + ul.sort),
                            Seq(NonTerminal(ul.childSort), NonTerminal(Sort(ul.sort + "#Terminator"))),
                            ul.attrs.add("klabel", ul.klabel).add(Constants.ORIGINAL_PRD, ul.pList));
                    // Es ::= Ne#Es
                    prod4 = Production(ul.sort, Seq(NonTerminal(Sort("Ne#" + ul.sort))));
                    // Es ::= Es#Terminator // if the list is *
                    prod5 = Production(ul.sort, Seq(NonTerminal(Sort(ul.sort + "#Terminator"))));

                    newProds.add(prod1);
                    newProds.add(prod2);
                    newProds.add(prod3);
                    newProds.add(prod4);
                    newProds.add(SyntaxSort(Sort(ul.sort + "#Terminator")));
                    newProds.add(SyntaxSort(Sort("Ne#" + ul.sort)));
                    if (!ul.nonEmpty) {
                        newProds.add(prod5);
                    }
                }
                // eliminate the general list productions
                newProds = newProds.stream().filter(p -> !(p instanceof Production && p.att().contains(KOREtoKIL.USER_LIST_ATTRIBUTE))).collect(Collectors.toSet());
                return Module(m.name(), m.imports(), immutable(newProds), m.att());
            }
            return m;
        }, "Generate Parsing parser").apply(disambM);

        return new ParseInModule(seedMod, extensionM, disambM, parseM, this.strict);
    }

    private Set<Sentence> makeCasts(Sort outerSort, Sort innerSort, Sort castSort) {
        Set<Sentence> prods = new HashSet<>();
        Att attrs1 = Att().add(Attribute.SORT_KEY, castSort.name());
        prods.add(Production("#SyntacticCast", castSort, Seq(NonTerminal(castSort), Terminal("::" + castSort.name())), attrs1));
        prods.add(Production("#SemanticCastTo" + castSort.name(), castSort, Seq(NonTerminal(castSort), Terminal(":" + castSort.name())), attrs1));
        prods.add(Production("#InnerCast", outerSort, Seq(NonTerminal(castSort), Terminal("<:" + castSort.name())), attrs1));
        prods.add(Production("#OuterCast", castSort, Seq(NonTerminal(innerSort), Terminal(":>" + castSort.name())), attrs1));
        return prods;
    }
}
