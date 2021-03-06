package org.antlr.intellij.plugin.parsing;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.adaptor.parser.SyntaxError;
import org.antlr.intellij.adaptor.parser.SyntaxErrorListener;
import org.antlr.intellij.plugin.ANTLRv4PluginController;
import org.antlr.intellij.plugin.PluginIgnoreMissingTokensFileErrorManager;
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer;
import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.intellij.plugin.preview.PreviewPanel;
import org.antlr.intellij.plugin.preview.PreviewState;
import org.antlr.v4.Tool;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.misc.Utils;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Trees;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ParsingUtils {
	public static Grammar BAD_PARSER_GRAMMAR;
	public static LexerGrammar BAD_LEXER_GRAMMAR;

	static {
		try {
			ParsingUtils.BAD_PARSER_GRAMMAR = new Grammar("grammar BAD; a : 'bad' ;");
			ParsingUtils.BAD_PARSER_GRAMMAR.name = "BAD_PARSER_GRAMMAR";
			ParsingUtils.BAD_LEXER_GRAMMAR = new LexerGrammar("lexer grammar BADLEXER; A : 'bad' ;");
			ParsingUtils.BAD_LEXER_GRAMMAR.name = "BAD_LEXER_GRAMMAR";
		}
		catch (org.antlr.runtime.RecognitionException re) {
			ANTLRv4PluginController.LOG.error("can't init bad grammar markers");
		}
	}

	public static Token nextRealToken(CommonTokenStream tokens, int i) {
		int n = tokens.size();
		i++; // search after current i token
		if ( i>=n || i<0 ) return null;
		Token t = tokens.get(i);
		while ( t.getChannel()==Token.HIDDEN_CHANNEL ) {
			if ( t.getType()==Token.EOF ) {
				TokenSource tokenSource = tokens.getTokenSource();
				if ( tokenSource==null ) {
					return new CommonToken(Token.EOF, "EOF");
				}
				TokenFactory<?> tokenFactory = tokenSource.getTokenFactory();
				if ( tokenFactory==null ) {
					return new CommonToken(Token.EOF, "EOF");
				}
				return tokenFactory.create(Token.EOF, "EOF");
			}
			i++;
			if ( i>=n ) return null; // just in case no EOF
			t = tokens.get(i);
		}
		return t;
	}

	public static Token previousRealToken(CommonTokenStream tokens, int i) {
		int size = tokens.size();
		i--; // search before current i token
		if ( i>=size || i<0 ) return null;
		Token t = tokens.get(i);
		while ( t.getChannel()==Token.HIDDEN_CHANNEL ) {
			i--;
			if ( i<0 ) return null;
			t = tokens.get(i);
		}
		return t;
	}

	public static Token getTokenUnderCursor(PreviewState previewState, int offset) {
		if ( previewState==null || previewState.parsingResult == null) return null;

		PreviewParser parser = (PreviewParser)previewState.parsingResult.parser;
		CommonTokenStream tokenStream =	(CommonTokenStream) parser.getInputStream();
		return ParsingUtils.getTokenUnderCursor(tokenStream, offset);
	}

	public static Token getTokenUnderCursor(CommonTokenStream tokens, int offset) {
		Comparator<Token> cmp = new Comparator<Token>() {
			@Override
			public int compare(Token a, Token b) {
				if ( a.getStopIndex() < b.getStartIndex() ) return -1;
				if ( a.getStartIndex() > b.getStopIndex() ) return 1;
				return 0;
			}
		};
		if ( offset<0 || offset >= tokens.getTokenSource().getInputStream().size() ) return null;
		CommonToken key = new CommonToken(Token.INVALID_TYPE, "");
		key.setStartIndex(offset);
		key.setStopIndex(offset);
		List<Token> tokenList = tokens.getTokens();
		Token tokenUnderCursor = null;
		int i = Collections.binarySearch(tokenList, key, cmp);
		if ( i>=0 ) tokenUnderCursor = tokenList.get(i);
		return tokenUnderCursor;
	}

	/*
	[77] = {org.antlr.v4.runtime.CommonToken@16710}"[@77,263:268='import',<25>,9:0]"
	[78] = {org.antlr.v4.runtime.CommonToken@16709}"[@78,270:273='java',<100>,9:7]"
	 */
	public static Token getSkippedTokenUnderCursor(CommonTokenStream tokens, int offset) {
		if ( offset<0 || offset >= tokens.getTokenSource().getInputStream().size() ) return null;
		Token prevToken = null;
		Token tokenUnderCursor = null;
		for (Token t : tokens.getTokens()) {
			int begin = t.getStartIndex();
			int end = t.getStopIndex();
			if ( (prevToken==null || offset > prevToken.getStopIndex()) && offset < begin ) {
				// found in between
				TokenSource tokenSource = tokens.getTokenSource();
				CharStream inputStream = null;
				if ( tokenSource!=null ) {
					inputStream = tokenSource.getInputStream();
				}
				tokenUnderCursor = new org.antlr.v4.runtime.CommonToken(
					new Pair<TokenSource, CharStream>(tokenSource, inputStream),
					Token.INVALID_TYPE,
					-1,
					prevToken!=null ? prevToken.getStopIndex()+1 : 0,
					begin-1
				);
				break;
			}
			if ( offset >= begin && offset <= end ) {
				tokenUnderCursor = t;
				break;
			}
			prevToken = t;
		}
		return tokenUnderCursor;
	}

	public static SyntaxError getErrorUnderCursor(java.util.List<SyntaxError> errors, int offset) {
		for (SyntaxError e : errors) {
			int a, b;
			RecognitionException cause = e.getException();
			if ( cause instanceof LexerNoViableAltException) {
				a = ((LexerNoViableAltException) cause).getStartIndex();
				b = ((LexerNoViableAltException) cause).getStartIndex()+1;
			}
			else {
				Token offendingToken = (Token)e.getOffendingSymbol();
				a = offendingToken.getStartIndex();
				b = offendingToken.getStopIndex()+1;
			}
			if ( offset >= a && offset < b ) { // cursor is over some kind of error
				return e;
			}
		}
		return null;
	}

	public static CommonTokenStream tokenizeANTLRGrammar(String text) {
		ANTLRInputStream input = new ANTLRInputStream(text);
		ANTLRv4Lexer lexer = new ANTLRv4Lexer(input);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();
		return tokens;
	}

    public static ParseTree getParseTreeNodeWithToken(ParseTree tree, Token token) {
        if ( tree==null || token==null ) {
            return null;
        }

        Collection<ParseTree> tokenNodes = Trees.findAllTokenNodes(tree, token.getType());
        for (ParseTree t : tokenNodes) {
            TerminalNode tnode = (TerminalNode)t;
            if ( tnode.getPayload() == token ) {
                return tnode;
            }
        }
        return null;
    }

    public static ParsingResult parseANTLRGrammar(String text) {
	    ANTLRInputStream input = new ANTLRInputStream(text);
		ANTLRv4Lexer lexer = new ANTLRv4Lexer(input);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		ANTLRv4Parser parser = new ANTLRv4Parser(tokens);

		SyntaxErrorListener listener = new SyntaxErrorListener();
		parser.removeErrorListeners();
		parser.addErrorListener(listener);
		lexer.removeErrorListeners();
		lexer.addErrorListener(listener);

		ParseTree t = parser.grammarSpec();
		return new ParsingResult(parser, t, listener);
	}

	/** Parse grammar text into v4 parse tree then look for tokenVocab=X */
	public static String getTokenVocabFromGrammar(String text) {
		// TODO: unneeded. use antlr Tool. Kill?
//		ParsingResult r = parseANTLRGrammar(text);
//		if ( r.tree!=null ) { //&& r.syntaxErrorListener.getSyntaxErrors().size()==0 ) {
//			// option : id ASSIGN optionValue ;
//			Collection<ParseTree> options = XPath.findAll(r.tree, "//option", r.parser);
//			for (Iterator<ParseTree> it = options.iterator(); it.hasNext(); ) {
//				ANTLRv4Parser.OptionContext option = (ANTLRv4Parser.OptionContext)it.next();
//				if ( option.id().getText().equals("tokenVocab") ) {
//					/*
//					optionValue
//						:	id (DOT id)*
//						|	STRING_LITERAL
//						|	ACTION
//						|	INT
//						;
//					 */
//					ANTLRv4Parser.OptionValueContext optionValue = option.optionValue();
//					if ( optionValue.STRING_LITERAL()!=null ) {
//						String s = optionValue.STRING_LITERAL().getText();
//						return RefactorUtils.getLexerRuleNameFromLiteral(s);
//					}
//					if ( optionValue.id(0)!=null ) {
//						return optionValue.id(0).getText();
//					}
//				}
//			}
//		}
		return null;
	}
	public static ParsingResult parseText(PreviewState previewState,
										  PreviewPanel previewPanel,
										  final VirtualFile grammarFile,
										  String inputText)
		throws IOException
	{
		ANTLRv4PluginController.LOG.info("parseText("+grammarFile.getName()+
										 ", input="+inputText.subSequence(0,Math.min(30, inputText.length()))+"...)");
		String grammarFileName = grammarFile.getPath();
		if (!new File(grammarFileName).exists()) {
			ANTLRv4PluginController.LOG.info("parseText grammar doesn't exist "+grammarFileName);
			return null;
		}

		if ( previewState.g==null || previewState.lg==null ) {
			ANTLRv4PluginController.LOG.info("parseText can't parse: missing lexer or parser no Grammar object for "+grammarFileName);
			return null;
		}

		if ( previewState.g==BAD_PARSER_GRAMMAR || previewState.lg==BAD_LEXER_GRAMMAR ) {
			return null;
		}

		ANTLRInputStream input = new ANTLRInputStream(inputText);
		LexerInterpreter lexEngine;
		lexEngine = previewState.lg.createLexerInterpreter(input);
		CommonTokenStream tokens = new CommonTokenStream(lexEngine);
		PreviewParser parser = new PreviewParser(previewState.g, tokens);
		parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		parser.setProfile(true);

		SyntaxErrorListener syntaxErrorListener = new SyntaxErrorListener();
		parser.removeErrorListeners();
		parser.addErrorListener(syntaxErrorListener);
		lexEngine.removeErrorListeners();
		lexEngine.addErrorListener(syntaxErrorListener);

		Rule start = previewState.g.getRule(previewState.startRuleName);
		if ( start==null ) {
			return null; // can't find start rule
		}
//		System.out.println("parse test ----------------------------");
		ParseTree t = parser.parse(start.index);

		if ( t!=null ) {
			return new ParsingResult(parser, t, syntaxErrorListener);
		}
		return null;
	}

	public static Tool createANTLRToolForLoadingGrammars() {
		Tool antlr = new Tool();
		antlr.errMgr = new PluginIgnoreMissingTokensFileErrorManager(antlr);
		antlr.errMgr.setFormat("antlr");
		LoadGrammarsToolListener listener = new LoadGrammarsToolListener(antlr);
		antlr.removeListeners();
		antlr.addListener(listener);
		return antlr;
	}

	/** Get lexer and parser grammars */
	public static Grammar[] loadGrammars(String grammarFileName, Project project) {
		ANTLRv4PluginController.LOG.info("loadGrammars "+grammarFileName+" "+project.getName());
		Tool antlr = createANTLRToolForLoadingGrammars();
		LoadGrammarsToolListener listener = (LoadGrammarsToolListener)antlr.getListeners().get(0);

		// basically here I am mimicking the loadGrammar() method from Tool
		// so that I can check for an empty AST coming back.
		ConsoleView console = ANTLRv4PluginController.getInstance(project).getConsole();
		GrammarRootAST grammarRootAST = antlr.parseGrammar(grammarFileName);
		if ( grammarRootAST==null ) {
			File f = new File(grammarFileName);
			String msg = "Empty or bad grammar in file "+f.getName();
			console.print(msg+"\n", ConsoleViewContentType.ERROR_OUTPUT);
			return null;
		}
		// Create a grammar from the AST so we can figure out what type it is
		Grammar g = antlr.createGrammar(grammarRootAST);
		g.fileName = grammarFileName;

		// see if a lexer is hanging around somewhere; don't want implicit token defs to make us bail
		LexerGrammar lg = null;
		if ( g.getType()==ANTLRParser.PARSER ) {
			lg = loadLexerGrammarFor(g, project);
			if ( lg!=null ) {
				g.importVocab(lg);
			}
			else {
				lg = BAD_LEXER_GRAMMAR;
			}
		}

		antlr.process(g, false);
		if ( listener.grammarErrorMessages.size()!=0 ) {
			String msg = Utils.join(listener.grammarErrorMessages.iterator(), "\n");
			console.print(msg+"\n", ConsoleViewContentType.ERROR_OUTPUT);
			return null; // upon error, bail
		}

		// Examine's Grammar AST constructed by v3 for a v4 grammar.
		// Use ANTLR v3's ANTLRParser not ANTLRv4Parser from this plugin
		switch ( g.getType() ) {
			case ANTLRParser.PARSER :
				ANTLRv4PluginController.LOG.info("loadGrammars parser "+g.name);
				return new Grammar[] {lg, g};
			case ANTLRParser.LEXER :
				ANTLRv4PluginController.LOG.info("loadGrammars lexer "+g.name);
				lg = (LexerGrammar)g;
				return new Grammar[] {lg, null};
			case ANTLRParser.COMBINED :
				lg = g.getImplicitLexer();
				if ( lg==null ) {
					lg = BAD_LEXER_GRAMMAR;
				}
				ANTLRv4PluginController.LOG.info("loadGrammars combined: "+lg.name+", "+g.name);
				return new Grammar[] {lg, g};
		}
		ANTLRv4PluginController.LOG.info("loadGrammars invalid grammar type "+g.getTypeString()+" for "+g.name);
		return null;
	}

	/** Try to load a LexerGrammar given a parser grammar g. Derive lexer name
	 *  as:
	 *  	V given tokenVocab=V in grammar or
	 *   	XLexer given XParser.g4 filename or
	 *     	XLexer given grammar name X
	 */
	public static LexerGrammar loadLexerGrammarFor(Grammar g, Project project) {
		Tool antlr = createANTLRToolForLoadingGrammars();
		LoadGrammarsToolListener listener = (LoadGrammarsToolListener)antlr.getListeners().get(0);
		LexerGrammar lg = null;
		String lexerGrammarFileName;

		String vocabName = g.getOptionString("tokenVocab");
		if ( vocabName!=null ) {
			File f = new File(g.fileName);
			File lexerF = new File(f.getParentFile(), vocabName + ".g4");
			lexerGrammarFileName = lexerF.getAbsolutePath();
		}
		else {
			lexerGrammarFileName = getLexerNameFromParserFileName(g.fileName);
		}

		File lf = new File(lexerGrammarFileName);
		if ( lf.exists() ) {
			try {
				lg = (LexerGrammar)antlr.loadGrammar(lexerGrammarFileName);
			}
			catch (ClassCastException cce) {
				ANTLRv4PluginController.LOG.error("File "+lexerGrammarFileName+" isn't a lexer grammar", cce);
			}
			catch (Exception e) {
				String msg = null;
				if ( listener.grammarErrorMessages.size()!=0 ) {
					msg = ": "+listener.grammarErrorMessages.toString();
				}
				ANTLRv4PluginController.LOG.error("File "+lexerGrammarFileName+" couldn't be parsed as a lexer grammar"+msg, e);
			}
			if ( listener.grammarErrorMessages.size()!=0 ) {
				lg = null;
				ConsoleView console = ANTLRv4PluginController.getInstance(project).getConsole();
				String msg = Utils.join(listener.grammarErrorMessages.iterator(), "\n");
				console.print(msg+"\n", ConsoleViewContentType.ERROR_OUTPUT);
			}
		}
		return lg;
	}

	@NotNull
	public static String getLexerNameFromParserFileName(String parserFileName) {
		String lexerGrammarFileName;
		int i = parserFileName.indexOf("Parser.g4");
		if ( i>=0 ) { // is filename XParser.g4?
			lexerGrammarFileName = parserFileName.substring(0, i) + "Lexer.g4";
		}
		else { // if not, try using the grammar name, XLexer.g4
			File f = new File(parserFileName);
			String fname = f.getName();
			int dot = fname.lastIndexOf(".g4");
			String parserName = fname.substring(0, dot);
			File parentDir = f.getParentFile();
			lexerGrammarFileName = new File(parentDir, parserName+"Lexer.g4").getAbsolutePath();
		}
		return lexerGrammarFileName;
	}

	/** Same as loadGrammar(fileName) except import vocab from existing lexer */
	public static Grammar loadGrammar(Tool tool, String fileName, LexerGrammar lexerGrammar) {
		GrammarRootAST grammarRootAST = tool.parseGrammar(fileName);
		if ( grammarRootAST==null ) return null;
		final Grammar g = tool.createGrammar(grammarRootAST);
		g.fileName = fileName;
		if ( lexerGrammar!=null ) {
            g.importVocab(lexerGrammar);
        }
		tool.process(g, false);
		return g;
	}
}
