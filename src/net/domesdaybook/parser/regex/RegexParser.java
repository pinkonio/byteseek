/*
 * Copyright Matt Palmer 2009-2011, All rights reserved.
 *
 * This code is licensed under a standard 3-clause BSD license:
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice, 
 *    this list of conditions and the following disclaimer.
 * 
 *  * Redistributions in binary form must reproduce the above copyright notice, 
 *    this list of conditions and the following disclaimer in the documentation 
 *    and/or other materials provided with the distribution.
 * 
 *  * The names of its contributors may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.domesdaybook.parser.regex;

import net.domesdaybook.parser.ParseException;
import net.domesdaybook.parser.Parser;
import net.domesdaybook.parser.tree.ParseTree;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.CommonTreeAdaptor;

/**
 * Parses a regular expression into an Abstract Syntax Tree (ast), using ANTLR
 * generated code to parse the expression. http://www.antlr.org
 * 
 * The parser {@link AntlrRegexParser} and the lexer
 * {@link AntlrRegexLexer} are generated by ANTLR from the grammar
 * specification file: AntlrRegex.g
 * 
 * The syntax of the expression is described in the file syntax.txt.
 * 
 * @author Matt Palmer palmer
 */
public class RegexParser implements Parser<ParseTree> {

	private static CommonTreeAdaptor antlrParseTreeAdaptor = new AntlrParseTreeAdaptor();
	
	/**
     * 
     */
	public RegexParser() {
	}

	/**
	 * Returns an (unoptimised) abstract syntax tree from a regular expression
	 * string.
	 * 
	 * @param expression
	 *            The expression to parse.
	 * @return An Abstract Syntax Tree
	 * @throws ParseException
	 *             If the expression could not be parsed.
	 */
	@Override
	public ParseTree parse(final String expression) throws ParseException {
		if (expression == null || expression.isEmpty()) {
			throw new ParseException("Null or empty expression passed in.");
		}
		try {
			return parseToAbstractSyntaxTree(expression);
		} catch (final RecognitionException ex) {
			throw new ParseException(ex);
		}
	}

	/**
	 * Performs the actual parse of an expression using the ANTLR-generated
	 * lexer and parser.
	 * 
	 * @param expression
	 *            The expression to parse.
	 * @return An abstract syntax tree representing the expression.
	 * @throws ParseException
	 *             If the expression could not be parsed.
	 * @throws RecognitionException
	 *             If the expression could be parsed by ANTLR.
	 */
	private ParseTree parseToAbstractSyntaxTree(final String expression)
			throws ParseException, RecognitionException {
		final ANTLRStringStream input = new ANTLRStringStream(expression);
		try {
			final AntlrRegexLexer lexer = new AntlrRegexLexer(input) {
				@Override
				public void reportError(RecognitionException e) {
					throw new AntlrParseException(e.getMessage(), e);
				}
			};
			
			if (lexer.getNumberOfSyntaxErrors() == 0) {
				final CommonTokenStream tokens = new CommonTokenStream(lexer);
				final AntlrRegexParser parser = new AntlrRegexParser(tokens) {
					@Override
					public void emitErrorMessage(final String msg) {
						throw new AntlrParseException(msg);
					}
				};
				
				parser.setTreeAdaptor(antlrParseTreeAdaptor);
				AntlrRegexParser.start_return result = parser.start();
				return (ParseTree) result.getTree();
			}
		} catch (final AntlrParseException e) {
			throw new ParseException(e.getMessage(), e);
		}
		throw new ParseException("Unknown problem occurred parsing: " + expression);
	}


	private static final class AntlrParseException extends RuntimeException {
		public AntlrParseException(final String message) {
			super(message);
		}
		public AntlrParseException(final String message, final Throwable cause) {
			super(message, cause);
		}
	}

}
