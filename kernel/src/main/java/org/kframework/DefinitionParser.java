// Copyright (c) 2015-2016 K Team. All Rights Reserved.
package org.kframework;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.inject.util.Providers;
import org.kframework.attributes.Source;
import org.kframework.definition.Definition;
import org.kframework.kompile.DefinitionParsing;
import org.kframework.kompile.Kompile;
import org.kframework.main.GlobalOptions;
import org.kframework.parser.concrete2kore.ParserUtils;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.file.FileUtil;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@API
public class DefinitionParser {

    /**
     * Parses the text to create a {@link Definition} object.
     * The main module of the definition will be last module defined in the text file.
     */
    public static org.kframework.definition.Definition from(String definitionText) {
        Pattern pattern = Pattern.compile("(?:^|\\s)module ([A-Z][A-Z\\-]*)");
        Matcher m = pattern.matcher(definitionText);
        if(!m.find()) {
            throw new RuntimeException("Could not find any module in the definition");
        }
        String nameOfLastModule = m.group(m.groupCount());
        return from(definitionText, nameOfLastModule, Source.apply("generated"));
    }

    /**
     * Parses the text to create a {@link Definition} object.
     */
    public static org.kframework.definition.Definition from(String definitionText, String mainModuleName) {
        return from(definitionText, mainModuleName, Source.apply("generated"));
    }

    /**
     * Parses the text to create a {@link Definition} object.
     */
    public static org.kframework.definition.Definition from(String definitionText, String mainModuleName, Source source) {
        return from(definitionText, mainModuleName, source, Lists.newArrayList(Kompile.BUILTIN_DIRECTORY));
    }

    /**
     * Parses the text to create a {@link Definition} object.
     */
    public static org.kframework.definition.Definition from(String definitionText, String mainModuleName, Source source, List<File> lookupDirectories) {
        GlobalOptions globalOptions = new GlobalOptions();
        KExceptionManager kem = new KExceptionManager(globalOptions);
        FileUtil fileUtil = FileUtil.get(globalOptions, System.getenv());
        ParserUtils parserUtils = new ParserUtils(fileUtil::resolveWorkingDirectory, kem, globalOptions);

        DefinitionParsing definitionParsing = new DefinitionParsing(lookupDirectories, true, kem, parserUtils, false, null, false);
        Definition definition = definitionParsing.parseDefinitionAndResolveBubbles(definitionText, mainModuleName, mainModuleName, source, lookupDirectories);

        return definition;
    }
}
