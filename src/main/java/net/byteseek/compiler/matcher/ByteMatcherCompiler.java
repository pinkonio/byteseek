/*
 * Copyright Matt Palmer 2009-2012, All rights reserved.
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

package net.byteseek.compiler.matcher;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.byteseek.matcher.bytes.ByteMatcherFactory;
import net.byteseek.matcher.bytes.OptimalByteMatcherFactory;
import net.byteseek.utils.ByteUtils;
import net.byteseek.compiler.AbstractCompiler;
import net.byteseek.compiler.CompileException;
import net.byteseek.matcher.bytes.ByteMatcher;
import net.byteseek.parser.ParseException;
import net.byteseek.parser.Parser;
import net.byteseek.parser.regex.RegexParser;
import net.byteseek.parser.tree.ParseTree;
import net.byteseek.parser.tree.ParseTreeType;
import net.byteseek.parser.tree.node.ChildrenNode;

/**
 * A compiler which produces a {@link ByteMatcher} from an
 * abstract syntax tree provided by the {@link AbstractCompiler} class,
 * which it extends.
 * <p>
 * It will interpret all regular expression syntax, calculating the union
 * of all byte values which the regular expression could match.
 * Hence, any regular expression can be fed to this compiler,
 * the resulting ByteMatcher matching any byte value which the full regular expression 
 * could match at any point in the expression.
 * <p>
 * For example, the following byteseek regex syntax all produces a ByteMatcher which matches the same
 * set of three bytes:
 * <p><blockquote><pre><code>
 *  01 02 03     # A sequence of three bytes: 01, 02 and 03.
 *  01|02|03     # Alternative bytes: 01, 02 and 03.
 * [01 02 03]    # A set of bytes: 01, 02 and 03.
 * [01 -  03]    # A range of bytes from 01 to 03. 
 * (01 02 03)+   # A sequence repeated from 1 to many times of three bytes: 01, 02 and 03.
 * </code></pre></blockquote><p><p> 
 * @author Matt Palmer
 */
public class ByteMatcherCompiler extends AbstractCompiler<ByteMatcher, ParseTree> {

	// Protected static final constants:

	protected static final boolean		NOT_INVERTED	= false;
	protected static final boolean		INVERTED		= true;

	// Protected static fields:

	protected static ByteMatcherCompiler defaultCompiler;
	protected static ByteMatcherFactory defaultFactory;

	/**
	 * Compiles a {@link ByteMatcher} from the expression (assuming the syntax
	 * provided results in a match for a single byte), using a ByteMatcherCompiler.
	 * 
	 * @param expression The regular expression syntax
	 * @return ByteMatcher A ByteMatcher which matches a byte according to the expression.
	 * @throws CompileException If a ByteMatcher cannot be produced from the expression.
	 */
	public static ByteMatcher compileFrom(final String expression) throws CompileException {
		defaultCompiler = new ByteMatcherCompiler();
		return defaultCompiler.compile(expression);
	}

	/**
	 * Returns a {@link ByteMatcher} which optimally matches the set of 
	 * bytes provided in the array.  It uses a @link SimpleByteMatcherFactory}
	 * to produce an appropriate matcher.
	 * 
	 * @param bytes An array of bytes containing the values the ByteMatcher must match.
	 * @return ByteMatcher a matcher which matches the byte values in the array provided.
	 */
	public static ByteMatcher compileFrom(final byte[] bytes) {
		defaultFactory = OptimalByteMatcherFactory.FACTORY;
		final Set<Byte> byteSet = ByteUtils.toSet(bytes);
		return defaultFactory.create(byteSet, NOT_INVERTED);
	}

	/**
	 * Returns a {@link ByteMatcher} which optimally matches the inverted set of 
	 * bytes provided in the array.  It uses a @link SimpleByteMatcherFactory}
	 * to produce an appropriate matcher.
	 * 
	 * @param bytes An array of bytes containing the values the ByteMatcher
	 *              must not match.
	 * @return ByteMatcher a matcher which matches all byte values other 
	 *         than those in the array provided.
	 */
	public static ByteMatcher compileInvertedFrom(final byte[] bytes) {
		defaultFactory = OptimalByteMatcherFactory.FACTORY;
		final Set<Byte> byteSet = ByteUtils.toSet(bytes);
		return defaultFactory.create(byteSet, INVERTED);
	}

	// Fields:

	private final ByteMatcherFactory	matcherFactory;

	// Constructors:

	/**
	 * Constructs a ByteMatcherCompiler using a {@link OptimalByteMatcherFactory}
	 * to construct optimal matchers for sets of bytes, and the default parser
	 * defined in AbstractCompiler.
	 * 
	 */
	public ByteMatcherCompiler() {
		this(null, null);
	}

	/**
	 * Constructs a ByteMatcherCompiler using the provided factory
	 * to construct optimal matchers for sets of bytes.  The parser 
	 * that produces the abstract syntax tree will the default one
	 * defined in AbstractCompiler.
	 * 
	 * @param factoryToUse The factory used to create optimal matchers for sets of bytes.
	 */
	public ByteMatcherCompiler(final ByteMatcherFactory factoryToUse) {
		this(null, factoryToUse);
	}
	
	/**
	 * Constructs a ByteMatcherCompiler using the provided parser.  The factory
	 * used to construct matchers from sets of bytes will be the default
	 * SimpleByteMatcherFactory.
	 * 
	 * @param parser The parser to use to produce an abstract syntax tree.
	 */
	public ByteMatcherCompiler(final Parser<ParseTree> parser) {
		this(parser, null);
	}
	
	
	/**
	 * Constructs a ByteMatcherCompiler using the provided parser and factory.
	 * <p>
	 * If the parser is null, then the default compiler defined in AbstractCompiler will be used.
	 * If the factory is null, then a SimpleByteMatcherFactory will be used.
	 * 
	 * @param parser The parser to use to produce an abstract syntax tree.
	 */
	public ByteMatcherCompiler(final Parser<ParseTree> parser, final ByteMatcherFactory factoryToUse) {
		super(parser == null? new RegexParser() : parser);
		matcherFactory = factoryToUse == null? OptimalByteMatcherFactory.FACTORY : factoryToUse;
	}


	/**
	 * Compiles a ByteMatcher which matches all of the bytes in the expressions.
	 * 
	 * @param expressions A collection of expression to compile.
	 * @return ByteMatcher a ByteMatcher which matches all the bytes in the expressions.
	 * @throws CompileException If the expressions could not be compiled.
	 */
	@Override
	public ByteMatcher compile(final Collection<String> expressions) throws CompileException {
		final Set<Byte> bytesToMatch = new LinkedHashSet<Byte>();
		for (final String expression : expressions) {
			final byte[] matchingBytes = compile(expression).getMatchingBytes();
			bytesToMatch.addAll(ByteUtils.toList(matchingBytes));
		}
		return matcherFactory.create(bytesToMatch, NOT_INVERTED);
	}

	/**
	 * Performs the actual compilation of a byte matcher from an abstract syntax tree.
	 *
	 * @param node The abstract syntax tree node to compile.
	 * @return A ByteMatcher representing the expression.
	 * @throws ParseException If the ast could not be parsed.
	 */
	@Override
	protected ByteMatcher doCompile(final ParseTree node) throws ParseException {

		switch (node.getParseTreeType()) {
			case BYTE: 			return MatcherCompilerUtils.createByteMatcher(node);
			case ANY:			return MatcherCompilerUtils.createAnyMatcher(node);
			case ALL_BITMASK:	return MatcherCompilerUtils.createAllBitmaskMatcher(node);
			case ANY_BITMASK:	return MatcherCompilerUtils.createAnyBitmaskMatcher(node);
			case RANGE: 		return MatcherCompilerUtils.createRangeMatcher(node);
			case SET:			return MatcherCompilerUtils.createMatcherFromSet(node, matcherFactory);
		}
		
		// The node type wasn't understood by this compiler.
		throw new ParseException(getTypeErrorMessage(node));
	}

	
	@Override
	protected ParseTree joinExpressions(List<ParseTree> expressions) 
			throws ParseException, CompileException {
		return new ChildrenNode(ParseTreeType.SET, expressions, NOT_INVERTED);
	}
	
	
	private String getTypeErrorMessage(final ParseTree node) {
		final ParseTreeType type = node.getParseTreeType();
		return String.format("Unknown syntax tree node, type [%s]", type);	
	}

}
