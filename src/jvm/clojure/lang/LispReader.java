/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

package clojure.lang;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.lang.Character;
import java.lang.Class;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.IllegalStateException;
import java.lang.Integer;
import java.lang.Number;
import java.lang.NumberFormatException;
import java.lang.Object;
import java.lang.RuntimeException;
import java.lang.String;
import java.lang.StringBuilder;
import java.lang.Throwable;
import java.lang.UnsupportedOperationException;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * 在学习 sicp 的时候写过 scheme 解释器，最核心的就两个函数： analyse 和 eval。这里的 LispReader 就扮演 analyse 的角色。
 * 我过去用 clojure 写过 scheme 解释器：https://github.com/killme2008/cscheme
 * 
 * reader 读取类型的完全定义 http://clojure.org/reader
 **/
public class LispReader{

//预定义一些 symbol，将在读取过程中用到
static final Symbol QUOTE = Symbol.intern("quote");
static final Symbol THE_VAR = Symbol.intern("var");
//static Symbol SYNTAX_QUOTE = Symbol.intern(null, "syntax-quote");
//指向clojure.core 中的部分读取过程中要用到的 symbol
static Symbol UNQUOTE = Symbol.intern("clojure.core", "unquote");
static Symbol UNQUOTE_SPLICING = Symbol.intern("clojure.core", "unquote-splicing");
static Symbol CONCAT = Symbol.intern("clojure.core", "concat");
static Symbol SEQ = Symbol.intern("clojure.core", "seq");
static Symbol LIST = Symbol.intern("clojure.core", "list");
static Symbol APPLY = Symbol.intern("clojure.core", "apply");
static Symbol HASHMAP = Symbol.intern("clojure.core", "hash-map");
static Symbol HASHSET = Symbol.intern("clojure.core", "hash-set");
static Symbol VECTOR = Symbol.intern("clojure.core", "vector");
static Symbol WITH_META = Symbol.intern("clojure.core", "with-meta");
static Symbol META = Symbol.intern("clojure.core", "meta");
static Symbol DEREF = Symbol.intern("clojure.core", "deref");
//特指 *read-eval* 的 :unknown 值
static Keyword UNKNOWN = Keyword.intern(null, "unknown");
//static Symbol DEREF_BANG = Symbol.intern("clojure.core", "deref!");

//特殊宏字符到 Reader 函数的映射，具体见下文 static 块
static IFn[] macros = new IFn[256];
// #开始的 dispatch 宏，具体见下文 static 块
static IFn[] dispatchMacros = new IFn[256];
//static Pattern symbolPat = Pattern.compile("[:]?([\\D&&[^:/]][^:/]*/)?[\\D&&[^:/]][^:/]*");
//symbol的正则表达式，注意 . 或者 : 前后开始或者结尾的都是 clojure 内部保留
static Pattern symbolPat = Pattern.compile("[:]?([\\D&&[^/]].*/)?(/|[\\D&&[^/]][^/]*)");
//static Pattern varPat = Pattern.compile("([\\D&&[^:\\.]][^:\\.]*):([\\D&&[^:\\.]][^:\\.]*)");
//static Pattern intPat = Pattern.compile("[-+]?[0-9]+\\.?");
//整数的正则表达式,注意结尾的 N，表示BigInt 2r1010 表示以2基数的二进制数，基数从 0 - 36
static Pattern intPat =
		Pattern.compile(
				"([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[0-9]+)(N)?");
//分数的正则表达式
static Pattern ratioPat = Pattern.compile("([-+]?[0-9]+)/([0-9]+)");
//浮点数的正则表达式,注意 M 结尾表示 BigDecimals
static Pattern floatPat = Pattern.compile("([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?");
//static Pattern accessorPat = Pattern.compile("\\.[a-zA-Z_]\\w*");
//static Pattern instanceMemberPat = Pattern.compile("\\.([a-zA-Z_][\\w\\.]*)\\.([a-zA-Z_]\\w*)");
//static Pattern staticMemberPat = Pattern.compile("([a-zA-Z_][\\w\\.]*)\\.([a-zA-Z_]\\w*)");
//static Pattern classNamePat = Pattern.compile("([a-zA-Z_][\\w\\.]*)\\.");

//symbol->gensymbol
// gensym 映射的 binding 的 key 值
static Var GENSYM_ENV = Var.create(null).setDynamic();
//sorted-map num->gensymbol
//处理匿名参数列表的 binding 的 key 值，例如 #(println % %1 %2)中的 %, %1, %2
static Var ARG_ENV = Var.create(null).setDynamic();
//构造器 reader，例如 (java.util.Date.)
static IFn ctorReader = new CtorReader();

static
	{
	//注册各种宏符号对应的 reader
	macros['"'] = new StringReader();  // " 开头的使用字符串Reader
	macros[';'] = new CommentReader();  // 注释
	macros['\''] = new WrappingReader(QUOTE); // quote 
	macros['@'] = new WrappingReader(DEREF);// deref
	macros['^'] = new MetaReader();   //元数据
	macros['`'] = new SyntaxQuoteReader(); // syntax quote
	macros['~'] = new UnquoteReader();   // unquote
	macros['('] = new ListReader();      //list 
	macros[')'] = new UnmatchedDelimiterReader();  //括号不匹配
	macros['['] = new VectorReader();   //vector
	macros[']'] = new UnmatchedDelimiterReader();  // 中括号不匹配
	macros['{'] = new MapReader();     // map
	macros['}'] = new UnmatchedDelimiterReader();  // 大括号不匹配
//	macros['|'] = new ArgVectorReader();
	macros['\\'] = new CharacterReader();   //字符，如\a
	macros['%'] = new ArgReader();   // 匿名函数便捷记法里的参数，如%, %1
	macros['#'] = new DispatchReader();  // #开头的dispatch宏


	//dispatch宏到reader的映射
	dispatchMacros['^'] = new MetaReader();  //元数据，老的形式 #^
	dispatchMacros['\''] = new VarReader();   //读取var，#'a，所谓var-quote
	dispatchMacros['"'] = new RegexReader();  //正则，#"[a-b]"
	dispatchMacros['('] = new FnReader();    //匿名函数快速记法 #(println 3)
	dispatchMacros['{'] = new SetReader();   // #{1} 集合
	dispatchMacros['='] = new EvalReader();  // eval reader，支持 var 和 list的eval
	dispatchMacros['!'] = new CommentReader();  //注释宏, #!开头的行将被忽略
	dispatchMacros['<'] = new UnreadableReader();   // #< 不可读
	dispatchMacros['_'] = new DiscardReader();   //#_ 丢弃
	}
/**
 * 空格和逗号都被当成“空格”忽略。
 * @param ch
 * @return
 */
static boolean isWhitespace(int ch){
	return Character.isWhitespace(ch) || ch == ',';
}

/**
 * 回退一个字符
 * @param r
 * @param ch
 */
static void unread(PushbackReader r, int ch) {
	if(ch != -1)
		try
			{
			r.unread(ch);
			}
		catch(IOException e)
			{
			throw Util.sneakyThrow(e);
			}
}

/**
 * Reader读取过程中抛出的异常，包括行号和列号，通过PushbackReader得到
 * @author dennis
 *
 */
public static class ReaderException extends RuntimeException{
	final int line;
	final int column;

	public ReaderException(int line, int column, Throwable cause){
		super(cause);
		this.line = line;
		this.column = column;
	}
}
/**
 * 读取一个字符串
 * @param r
 * @return
 */
static public int read1(Reader r){
	try
		{
		return r.read();
		}
	catch(IOException e)
		{
		throw Util.sneakyThrow(e);
		}
}

/**
 * LispReader 入口，从这里读出一个一个 object
 * @param r 读取的io reader
 * @param eofIsError  如果读取到末尾，是否抛出异常
 * @param eofValue  到达末尾返回的值，如果eofIsError，忽略此参数，直接抛出异常
 * @param isRecursive  是否是递归调用
 * @return 返回一个'object'
 */
static public Object read(PushbackReader r, boolean eofIsError, Object eofValue, boolean isRecursive)
{
	//当 *read-eval* 是 unknown 的时候，抛出异常
	if(RT.READEVAL.deref() == UNKNOWN)
		throw Util.runtimeException("Reading disallowed - *read-eval* bound to :unknown");

	try
		{
		//嗯，任何一个 analyzer 都是一个 while 循环
		for(; ;)
			{
			//1.读取一个字符
			int ch = read1(r);

			//2.忽略“空白”
			while(isWhitespace(ch))
				ch = read1(r);
			//3.如果到达结尾
			if(ch == -1)
				{
				//3.1 如果eofIsError为true，直接抛出异常
				if(eofIsError)
					throw Util.runtimeException("EOF while reading");
				//3.2 否则返回 eofValue
				return eofValue;
				}
			//4.如果是数字
			if(Character.isDigit(ch))
				{
				//尝试读取数字，塞入初始字符ch
				Object n = readNumber(r, (char) ch);
				//注重 suppress-read 选项，目前写死为 false
				//http://dev.clojure.org/jira/browse/TRDR-14
				//下面的读取都有这个处理，不再重复注释
				if(RT.suppressRead())
					return null;
				return n;
				}
			//5.如果是 macro 字符，从 macros table尝试找一个 reader
			IFn macroFn = getMacro(ch);
			//5.1如果 reader存在，使用 reader 读取
			if(macroFn != null)
				{
				//调用 reader fn
				Object ret = macroFn.invoke(r, (char) ch);
				if(RT.suppressRead())
					return null;
				//no op macros return the reader
				//小约定，如果返回的是 reader，那么继续读，所谓no op reader
				if(ret == r)
					continue;
				return ret;
				}
			//6.如果是加减符号
			if(ch == '+' || ch == '-')
				{
				//预读一个字符
				int ch2 = read1(r);
				//6.1 如果加减符号后面接着数字，也就是正负数 +3, -2 等
				if(Character.isDigit(ch2))
					{
					//将 ch2 塞回去
					unread(r, ch2);
					//尝试读取数字，传入初始字符ch
					Object n = readNumber(r, (char) ch);
					if(RT.suppressRead())
						return null;
					//分那会数字
					return n;
					}
				//6.2 否则，塞回ch2，继续往下走
				unread(r, ch2);
				}
			//7.尝试读取一个 token
			String token = readToken(r, (char) ch);
			if(RT.suppressRead())
				return null;
			//8.解释 token 含义
			return interpretToken(token);
			}
		}
	catch(Exception e)
		{
		//读取过程中发生异常
		//如果是递归调用，或者非 LineNumberingPushbackReader，直接抛出异常
		if(isRecursive || !(r instanceof LineNumberingPushbackReader))
			throw Util.sneakyThrow(e);
		//抛出 ReaderException,带上行号和列号
		LineNumberingPushbackReader rdr = (LineNumberingPushbackReader) r;
		//throw Util.runtimeException(String.format("ReaderError:(%d,1) %s", rdr.getLineNumber(), e.getMessage()), e);
		throw new ReaderException(rdr.getLineNumber(), rdr.getColumnNumber(), e);
		}
}

static private String readToken(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isTerminatingMacro(ch))
			{
			unread(r, ch);
			return sb.toString();
			}
		sb.append((char) ch);
		}
}

static private Object readNumber(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isMacro(ch))
			{
			unread(r, ch);
			break;
			}
		sb.append((char) ch);
		}

	String s = sb.toString();
	Object n = matchNumber(s);
	if(n == null)
		throw new NumberFormatException("Invalid number: " + s);
	return n;
}

static private int readUnicodeChar(String token, int offset, int length, int base) {
	if(token.length() != offset + length)
		throw new IllegalArgumentException("Invalid unicode character: \\" + token);
	int uc = 0;
	for(int i = offset; i < offset + length; ++i)
		{
		int d = Character.digit(token.charAt(i), base);
		if(d == -1)
			throw new IllegalArgumentException("Invalid digit: " + token.charAt(i));
		uc = uc * base + d;
		}
	return (char) uc;
}

static private int readUnicodeChar(PushbackReader r, int initch, int base, int length, boolean exact) {
	int uc = Character.digit(initch, base);
	if(uc == -1)
		throw new IllegalArgumentException("Invalid digit: " + (char) initch);
	int i = 1;
	for(; i < length; ++i)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isMacro(ch))
			{
			unread(r, ch);
			break;
			}
		int d = Character.digit(ch, base);
		if(d == -1)
			throw new IllegalArgumentException("Invalid digit: " + (char) ch);
		uc = uc * base + d;
		}
	if(i != length && exact)
		throw new IllegalArgumentException("Invalid character length: " + i + ", should be: " + length);
	return uc;
}

static private Object interpretToken(String s) {
	if(s.equals("nil"))
		{
		return null;
		}
	else if(s.equals("true"))
		{
		return RT.T;
		}
	else if(s.equals("false"))
		{
		return RT.F;
		}
	Object ret = null;

	ret = matchSymbol(s);
	if(ret != null)
		return ret;

	throw Util.runtimeException("Invalid token: " + s);
}


private static Object matchSymbol(String s){
	Matcher m = symbolPat.matcher(s);
	if(m.matches())
		{
		int gc = m.groupCount();
		String ns = m.group(1);
		String name = m.group(2);
		if(ns != null && ns.endsWith(":/")
		   || name.endsWith(":")
		   || s.indexOf("::", 1) != -1)
			return null;
		if(s.startsWith("::"))
			{
			Symbol ks = Symbol.intern(s.substring(2));
			Namespace kns;
			if(ks.ns != null)
				kns = Compiler.namespaceFor(ks);
			else
				kns = Compiler.currentNS();
			//auto-resolving keyword
			if (kns != null)
				return Keyword.intern(kns.name.name,ks.name);
			else
				return null;
			}
		boolean isKeyword = s.charAt(0) == ':';
		Symbol sym = Symbol.intern(s.substring(isKeyword ? 1 : 0));
		if(isKeyword)
			return Keyword.intern(sym);
		return sym;
		}
	return null;
}


private static Object matchNumber(String s){
	Matcher m = intPat.matcher(s);
	if(m.matches())
		{
		if(m.group(2) != null)
			{
			if(m.group(8) != null)
				return BigInt.ZERO;
			return Numbers.num(0);
			}
		boolean negate = (m.group(1).equals("-"));
		String n;
		int radix = 10;
		if((n = m.group(3)) != null)
			radix = 10;
		else if((n = m.group(4)) != null)
			radix = 16;
		else if((n = m.group(5)) != null)
			radix = 8;
		else if((n = m.group(7)) != null)
			radix = Integer.parseInt(m.group(6));
		if(n == null)
			return null;
		BigInteger bn = new BigInteger(n, radix);
		if(negate)
			bn = bn.negate();
		if(m.group(8) != null)
			return BigInt.fromBigInteger(bn);
		return bn.bitLength() < 64 ?
		       Numbers.num(bn.longValue())
		                           : BigInt.fromBigInteger(bn);
		}
	m = floatPat.matcher(s);
	if(m.matches())
		{
		if(m.group(4) != null)
			return new BigDecimal(m.group(1));
		return Double.parseDouble(s);
		}
	m = ratioPat.matcher(s);
	if(m.matches())
		{
		String numerator = m.group(1);
		if (numerator.startsWith("+")) numerator = numerator.substring(1);

		return Numbers.divide(Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(numerator))),
		                      Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(m.group(2)))));
		}
	return null;
}
/**
 * 查找 macros table，返回有效的reader
 * @param ch
 * @return
 */
static private IFn getMacro(int ch){
	if(ch < macros.length)
		return macros[ch];
	return null;
}

static private boolean isMacro(int ch){
	return (ch < macros.length && macros[ch] != null);
}

static private boolean isTerminatingMacro(int ch){
	return (ch != '#' && ch != '\'' && ch != '%' && isMacro(ch));
}

public static class RegexReader extends AFn{
	static StringReader stringrdr = new StringReader();

	public Object invoke(Object reader, Object doublequote) {
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;
		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			if(ch == -1)
				throw Util.runtimeException("EOF while reading regex");
			sb.append( (char) ch );
			if(ch == '\\')	//escape
				{
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading regex");
				sb.append( (char) ch ) ;
				}
			}
		return Pattern.compile(sb.toString());
	}
}

public static class StringReader extends AFn{
	public Object invoke(Object reader, Object doublequote) {
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;

		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			if(ch == -1)
				throw Util.runtimeException("EOF while reading string");
			if(ch == '\\')	//escape
				{
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading string");
				switch(ch)
					{
					case 't':
						ch = '\t';
						break;
					case 'r':
						ch = '\r';
						break;
					case 'n':
						ch = '\n';
						break;
					case '\\':
						break;
					case '"':
						break;
					case 'b':
						ch = '\b';
						break;
					case 'f':
						ch = '\f';
						break;
					case 'u':
					{
					ch = read1(r);
					if (Character.digit(ch, 16) == -1)
						throw Util.runtimeException("Invalid unicode escape: \\u" + (char) ch);
					ch = readUnicodeChar((PushbackReader) r, ch, 16, 4, true);
					break;
					}
					default:
					{
					if(Character.isDigit(ch))
						{
						ch = readUnicodeChar((PushbackReader) r, ch, 8, 3, false);
						if(ch > 0377)
							throw Util.runtimeException("Octal escape sequence must be in range [0, 377].");
						}
					else
						throw Util.runtimeException("Unsupported escape character: \\" + (char) ch);
					}
					}
				}
			sb.append((char) ch);
			}
		return sb.toString();
	}
}

public static class CommentReader extends AFn{
	public Object invoke(Object reader, Object semicolon) {
		Reader r = (Reader) reader;
		int ch;
		do
			{
			ch = read1(r);
			} while(ch != -1 && ch != '\n' && ch != '\r');
		return r;
	}

}

public static class DiscardReader extends AFn{
	public Object invoke(Object reader, Object underscore) {
		PushbackReader r = (PushbackReader) reader;
		read(r, true, null, true);
		return r;
	}
}

public static class WrappingReader extends AFn{
	final Symbol sym;

	public WrappingReader(Symbol sym){
		this.sym = sym;
	}

	public Object invoke(Object reader, Object quote) {
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
		return RT.list(sym, o);
	}

}

public static class DeprecatedWrappingReader extends AFn{
	final Symbol sym;
	final String macro;

	public DeprecatedWrappingReader(Symbol sym, String macro){
		this.sym = sym;
		this.macro = macro;
	}

	public Object invoke(Object reader, Object quote) {
		System.out.println("WARNING: reader macro " + macro +
		                   " is deprecated; use " + sym.getName() +
		                   " instead");
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
		return RT.list(sym, o);
	}

}

public static class VarReader extends AFn{
	public Object invoke(Object reader, Object quote) {
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
//		if(o instanceof Symbol)
//			{
//			Object v = Compiler.maybeResolveIn(Compiler.currentNS(), (Symbol) o);
//			if(v instanceof Var)
//				return v;
//			}
		return RT.list(THE_VAR, o);
	}
}

/*
static class DerefReader extends AFn{

	public Object invoke(Object reader, Object quote) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		if(ch == '!')
			{
			Object o = read(r, true, null, true);
			return RT.list(DEREF_BANG, o);
			}
		else
			{
			r.unread(ch);
			Object o = read(r, true, null, true);
			return RT.list(DEREF, o);
			}
	}

}
*/

public static class DispatchReader extends AFn{
	public Object invoke(Object reader, Object hash) {
		int ch = read1((Reader) reader);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		IFn fn = dispatchMacros[ch];

		// Try the ctor reader first
		if(fn == null) {
		unread((PushbackReader) reader, ch);
		Object result = ctorReader.invoke(reader, ch);

		if(result != null)
			return result;
		else
			throw Util.runtimeException(String.format("No dispatch macro for: %c", (char) ch));
		}
		return fn.invoke(reader, ch);
	}
}

static Symbol garg(int n){
	return Symbol.intern(null, (n == -1 ? "rest" : ("p" + n)) + "__" + RT.nextID() + "#");
}

public static class FnReader extends AFn{
	public Object invoke(Object reader, Object lparen) {
		PushbackReader r = (PushbackReader) reader;
		if(ARG_ENV.deref() != null)
			throw new IllegalStateException("Nested #()s are not allowed");
		try
			{
			Var.pushThreadBindings(
					RT.map(ARG_ENV, PersistentTreeMap.EMPTY));
			unread(r, '(');
			Object form = read(r, true, null, true);

			PersistentVector args = PersistentVector.EMPTY;
			PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
			ISeq rargs = argsyms.rseq();
			if(rargs != null)
				{
				int higharg = (Integer) ((Map.Entry) rargs.first()).getKey();
				if(higharg > 0)
					{
					for(int i = 1; i <= higharg; ++i)
						{
						Object sym = argsyms.valAt(i);
						if(sym == null)
							sym = garg(i);
						args = args.cons(sym);
						}
					}
				Object restsym = argsyms.valAt(-1);
				if(restsym != null)
					{
					args = args.cons(Compiler._AMP_);
					args = args.cons(restsym);
					}
				}
			return RT.list(Compiler.FN, args, form);
			}
		finally
			{
			Var.popThreadBindings();
			}
	}
}

static Symbol registerArg(int n){
	PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
	if(argsyms == null)
		{
		throw new IllegalStateException("arg literal not in #()");
		}
	Symbol ret = (Symbol) argsyms.valAt(n);
	if(ret == null)
		{
		ret = garg(n);
		ARG_ENV.set(argsyms.assoc(n, ret));
		}
	return ret;
}

static class ArgReader extends AFn{
	public Object invoke(Object reader, Object pct) {
		PushbackReader r = (PushbackReader) reader;
		if(ARG_ENV.deref() == null)
			{
			return interpretToken(readToken(r, '%'));
			}
		int ch = read1(r);
		unread(r, ch);
		//% alone is first arg
		if(ch == -1 || isWhitespace(ch) || isTerminatingMacro(ch))
			{
			return registerArg(1);
			}
		Object n = read(r, true, null, true);
		if(n.equals(Compiler._AMP_))
			return registerArg(-1);
		if(!(n instanceof Number))
			throw new IllegalStateException("arg literal must be %, %& or %integer");
		return registerArg(((Number) n).intValue());
	}
}

public static class MetaReader extends AFn{
	public Object invoke(Object reader, Object caret) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		Object meta = read(r, true, null, true);
		if(meta instanceof Symbol || meta instanceof String)
			meta = RT.map(RT.TAG_KEY, meta);
		else if (meta instanceof Keyword)
			meta = RT.map(meta, RT.T);
		else if(!(meta instanceof IPersistentMap))
			throw new IllegalArgumentException("Metadata must be Symbol,Keyword,String or Map");

		Object o = read(r, true, null, true);
		if(o instanceof IMeta)
			{
			if(line != -1 && o instanceof ISeq)
				{
				meta = ((IPersistentMap) meta).assoc(RT.LINE_KEY, line).assoc(RT.COLUMN_KEY, column);
				}
			if(o instanceof IReference)
				{
				((IReference)o).resetMeta((IPersistentMap) meta);
				return o;
				}
			Object ometa = RT.meta(o);
			for(ISeq s = RT.seq(meta); s != null; s = s.next()) {
			IMapEntry kv = (IMapEntry) s.first();
			ometa = RT.assoc(ometa, kv.getKey(), kv.getValue());
			}
			return ((IObj) o).withMeta((IPersistentMap) ometa);
			}
		else
			throw new IllegalArgumentException("Metadata can only be applied to IMetas");
	}

}

public static class SyntaxQuoteReader extends AFn{
	public Object invoke(Object reader, Object backquote) {
		PushbackReader r = (PushbackReader) reader;
		try
			{
			Var.pushThreadBindings(
					RT.map(GENSYM_ENV, PersistentHashMap.EMPTY));

			Object form = read(r, true, null, true);
			return syntaxQuote(form);
			}
		finally
			{
			Var.popThreadBindings();
			}
	}

	static Object syntaxQuote(Object form) {
		Object ret;
		if(Compiler.isSpecial(form))
			ret = RT.list(Compiler.QUOTE, form);
		else if(form instanceof Symbol)
			{
			Symbol sym = (Symbol) form;
			if(sym.ns == null && sym.name.endsWith("#"))
				{
				IPersistentMap gmap = (IPersistentMap) GENSYM_ENV.deref();
				if(gmap == null)
					throw new IllegalStateException("Gensym literal not in syntax-quote");
				Symbol gs = (Symbol) gmap.valAt(sym);
				if(gs == null)
					GENSYM_ENV.set(gmap.assoc(sym, gs = Symbol.intern(null,
					                                                  sym.name.substring(0, sym.name.length() - 1)
					                                                  + "__" + RT.nextID() + "__auto__")));
				sym = gs;
				}
			else if(sym.ns == null && sym.name.endsWith("."))
				{
				Symbol csym = Symbol.intern(null, sym.name.substring(0, sym.name.length() - 1));
				csym = Compiler.resolveSymbol(csym);
				sym = Symbol.intern(null, csym.name.concat("."));
				}
			else if(sym.ns == null && sym.name.startsWith("."))
				{
				// Simply quote method names.
				}
			else
				{
				Object maybeClass = null;
				if(sym.ns != null)
					maybeClass = Compiler.currentNS().getMapping(
							Symbol.intern(null, sym.ns));
				if(maybeClass instanceof Class)
					{
					// Classname/foo -> package.qualified.Classname/foo
					sym = Symbol.intern(
							((Class)maybeClass).getName(), sym.name);
					}
				else
					sym = Compiler.resolveSymbol(sym);
				}
			ret = RT.list(Compiler.QUOTE, sym);
			}
		else if(isUnquote(form))
			return RT.second(form);
		else if(isUnquoteSplicing(form))
			throw new IllegalStateException("splice not in list");
		else if(form instanceof IPersistentCollection)
			{
			if(form instanceof IRecord)
				ret = form;
			else if(form instanceof IPersistentMap)
				{
				IPersistentVector keyvals = flattenMap(form);
				ret = RT.list(APPLY, HASHMAP, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(keyvals.seq()))));
				}
			else if(form instanceof IPersistentVector)
				{
				ret = RT.list(APPLY, VECTOR, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(((IPersistentVector) form).seq()))));
				}
			else if(form instanceof IPersistentSet)
				{
				ret = RT.list(APPLY, HASHSET, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(((IPersistentSet) form).seq()))));
				}
			else if(form instanceof ISeq || form instanceof IPersistentList)
				{
				ISeq seq = RT.seq(form);
				if(seq == null)
					ret = RT.cons(LIST,null);
				else
					ret = RT.list(SEQ, RT.cons(CONCAT, sqExpandList(seq)));
				}
			else
				throw new UnsupportedOperationException("Unknown Collection type");
			}
		else if(form instanceof Keyword
		        || form instanceof Number
		        || form instanceof Character
		        || form instanceof String)
			ret = form;
		else
			ret = RT.list(Compiler.QUOTE, form);

		if(form instanceof IObj && RT.meta(form) != null)
			{
			//filter line and column numbers
			IPersistentMap newMeta = ((IObj) form).meta().without(RT.LINE_KEY).without(RT.COLUMN_KEY);
			if(newMeta.count() > 0)
				return RT.list(WITH_META, ret, syntaxQuote(((IObj) form).meta()));
			}
		return ret;
	}

	private static ISeq sqExpandList(ISeq seq) {
		PersistentVector ret = PersistentVector.EMPTY;
		for(; seq != null; seq = seq.next())
			{
			Object item = seq.first();
			if(isUnquote(item))
				ret = ret.cons(RT.list(LIST, RT.second(item)));
			else if(isUnquoteSplicing(item))
				ret = ret.cons(RT.second(item));
			else
				ret = ret.cons(RT.list(LIST, syntaxQuote(item)));
			}
		return ret.seq();
	}

	private static IPersistentVector flattenMap(Object form){
		IPersistentVector keyvals = PersistentVector.EMPTY;
		for(ISeq s = RT.seq(form); s != null; s = s.next())
			{
			IMapEntry e = (IMapEntry) s.first();
			keyvals = (IPersistentVector) keyvals.cons(e.key());
			keyvals = (IPersistentVector) keyvals.cons(e.val());
			}
		return keyvals;
	}

}

static boolean isUnquoteSplicing(Object form){
	return form instanceof ISeq && Util.equals(RT.first(form),UNQUOTE_SPLICING);
}

static boolean isUnquote(Object form){
	return form instanceof ISeq && Util.equals(RT.first(form),UNQUOTE);
}

static class UnquoteReader extends AFn{
	public Object invoke(Object reader, Object comma) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		if(ch == '@')
			{
			Object o = read(r, true, null, true);
			return RT.list(UNQUOTE_SPLICING, o);
			}
		else
			{
			unread(r, ch);
			Object o = read(r, true, null, true);
			return RT.list(UNQUOTE, o);
			}
	}

}

public static class CharacterReader extends AFn{
	public Object invoke(Object reader, Object backslash) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		String token = readToken(r, (char) ch);
		if(token.length() == 1)
			return Character.valueOf(token.charAt(0));
		else if(token.equals("newline"))
			return '\n';
		else if(token.equals("space"))
			return ' ';
		else if(token.equals("tab"))
			return '\t';
		else if(token.equals("backspace"))
			return '\b';
		else if(token.equals("formfeed"))
			return '\f';
		else if(token.equals("return"))
			return '\r';
		else if(token.startsWith("u"))
			{
			char c = (char) readUnicodeChar(token, 1, 4, 16);
			if(c >= '\uD800' && c <= '\uDFFF') // surrogate code unit?
				throw Util.runtimeException("Invalid character constant: \\u" + Integer.toString(c, 16));
			return c;
			}
		else if(token.startsWith("o"))
			{
			int len = token.length() - 1;
			if(len > 3)
				throw Util.runtimeException("Invalid octal escape sequence length: " + len);
			int uc = readUnicodeChar(token, 1, len, 8);
			if(uc > 0377)
				throw Util.runtimeException("Octal escape sequence must be in range [0, 377].");
			return (char) uc;
			}
		throw Util.runtimeException("Unsupported character: \\" + token);
	}

}

public static class ListReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		List list = readDelimitedList(')', r, true);
		if(list.isEmpty())
			return PersistentList.EMPTY;
		IObj s = (IObj) PersistentList.create(list);
//		IObj s = (IObj) RT.seq(list);
		if(line != -1)
			{
			return s.withMeta(RT.map(RT.LINE_KEY, line, RT.COLUMN_KEY, column));
			}
		else
			return s;
	}

}

/*
static class CtorReader extends AFn{
	static final Symbol cls = Symbol.intern("class");

	public Object invoke(Object reader, Object leftangle) {
		PushbackReader r = (PushbackReader) reader;
		// #<class classname>
		// #<classname args*>
		// #<classname/staticMethod args*>
		List list = readDelimitedList('>', r, true);
		if(list.isEmpty())
			throw Util.runtimeException("Must supply 'class', classname or classname/staticMethod");
		Symbol s = (Symbol) list.get(0);
		Object[] args = list.subList(1, list.size()).toArray();
		if(s.equals(cls))
			{
			return RT.classForName(args[0].toString());
			}
		else if(s.ns != null) //static method
			{
			String classname = s.ns;
			String method = s.name;
			return Reflector.invokeStaticMethod(classname, method, args);
			}
		else
			{
			return Reflector.invokeConstructor(RT.classForName(s.name), args);
			}
	}
}
*/

public static class EvalReader extends AFn{
	public Object invoke(Object reader, Object eq) {
		if (!RT.booleanCast(RT.READEVAL.deref()))
			{
			throw Util.runtimeException("EvalReader not allowed when *read-eval* is false.");
			}

		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
		if(o instanceof Symbol)
			{
			return RT.classForName(o.toString());
			}
		else if(o instanceof IPersistentList)
			{
			Symbol fs = (Symbol) RT.first(o);
			if(fs.equals(THE_VAR))
				{
				Symbol vs = (Symbol) RT.second(o);
				return RT.var(vs.ns, vs.name);  //Compiler.resolve((Symbol) RT.second(o),true);
				}
			if(fs.name.endsWith("."))
				{
				Object[] args = RT.toArray(RT.next(o));
				return Reflector.invokeConstructor(RT.classForName(fs.name.substring(0, fs.name.length() - 1)), args);
				}
			if(Compiler.namesStaticMember(fs))
				{
				Object[] args = RT.toArray(RT.next(o));
				return Reflector.invokeStaticMethod(fs.ns, fs.name, args);
				}
			Object v = Compiler.maybeResolveIn(Compiler.currentNS(), fs);
			if(v instanceof Var)
				{
				return ((IFn) v).applyTo(RT.next(o));
				}
			throw Util.runtimeException("Can't resolve " + fs);
			}
		else
			throw new IllegalArgumentException("Unsupported #= form");
	}
}

//static class ArgVectorReader extends AFn{
//	public Object invoke(Object reader, Object leftparen) {
//		PushbackReader r = (PushbackReader) reader;
//		return ArgVector.create(readDelimitedList('|', r, true));
//	}
//
//}

public static class VectorReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		return LazilyPersistentVector.create(readDelimitedList(']', r, true));
	}

}

public static class MapReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		Object[] a = readDelimitedList('}', r, true).toArray();
		if((a.length & 1) == 1)
			throw Util.runtimeException("Map literal must contain an even number of forms");
		return RT.map(a);
	}

}

public static class SetReader extends AFn{
	public Object invoke(Object reader, Object leftbracket) {
		PushbackReader r = (PushbackReader) reader;
		return PersistentHashSet.createWithCheck(readDelimitedList('}', r, true));
	}

}

public static class UnmatchedDelimiterReader extends AFn{
	public Object invoke(Object reader, Object rightdelim) {
		throw Util.runtimeException("Unmatched delimiter: " + rightdelim);
	}

}

public static class UnreadableReader extends AFn{
	public Object invoke(Object reader, Object leftangle) {
		throw Util.runtimeException("Unreadable form");
	}
}

public static List readDelimitedList(char delim, PushbackReader r, boolean isRecursive) {
	final int firstline =
			(r instanceof LineNumberingPushbackReader) ?
			((LineNumberingPushbackReader) r).getLineNumber() : -1;

	ArrayList a = new ArrayList();

	for(; ;)
		{
		int ch = read1(r);

		while(isWhitespace(ch))
			ch = read1(r);

		if(ch == -1)
			{
			if(firstline < 0)
				throw Util.runtimeException("EOF while reading");
			else
				throw Util.runtimeException("EOF while reading, starting at line " + firstline);
			}

		if(ch == delim)
			break;

		IFn macroFn = getMacro(ch);
		if(macroFn != null)
			{
			Object mret = macroFn.invoke(r, (char) ch);
			//no op macros return the reader
			if(mret != r)
				a.add(mret);
			}
		else
			{
			unread(r, ch);

			Object o = read(r, true, null, isRecursive);
			if(o != r)
				a.add(o);
			}
		}


	return a;
}

public static class CtorReader extends AFn{
	public Object invoke(Object reader, Object firstChar){
		PushbackReader r = (PushbackReader) reader;
		Object name = read(r, true, null, false);
		if (!(name instanceof Symbol))
			throw new RuntimeException("Reader tag must be a symbol");
		Symbol sym = (Symbol)name;
		return sym.getName().contains(".") ? readRecord(r, sym) : readTagged(r, sym);
	}

	private Object readTagged(PushbackReader reader, Symbol tag){
		Object o = read(reader, true, null, true);

		ILookup data_readers = (ILookup)RT.DATA_READERS.deref();
		IFn data_reader = (IFn)RT.get(data_readers, tag);
		if(data_reader == null){
		data_readers = (ILookup)RT.DEFAULT_DATA_READERS.deref();
		data_reader = (IFn)RT.get(data_readers, tag);
		if(data_reader == null){
		IFn default_reader = (IFn)RT.DEFAULT_DATA_READER_FN.deref();
		if(default_reader != null)
			return default_reader.invoke(tag, o);
		else
			throw new RuntimeException("No reader function for tag " + tag.toString());
		}
		}

		return data_reader.invoke(o);
	}

	private Object readRecord(PushbackReader r, Symbol recordName){
        boolean readeval = RT.booleanCast(RT.READEVAL.deref());

	    if(!readeval)
		    {
		    throw Util.runtimeException("Record construction syntax can only be used when *read-eval* == true");
		    }

		Class recordClass = RT.classForNameNonLoading(recordName.toString());

		char endch;
		boolean shortForm = true;
		int ch = read1(r);

		// flush whitespace
		while(isWhitespace(ch))
			ch = read1(r);

		// A defrecord ctor can take two forms. Check for map->R version first.
		if(ch == '{')
			{
			endch = '}';
			shortForm = false;
			}
		else if (ch == '[')
			endch = ']';
		else
			throw Util.runtimeException("Unreadable constructor form starting with \"#" + recordName + (char) ch + "\"");

		Object[] recordEntries = readDelimitedList(endch, r, true).toArray();
		Object ret = null;
		Constructor[] allctors = ((Class)recordClass).getConstructors();

		if(shortForm)
			{
			boolean ctorFound = false;
			for (Constructor ctor : allctors)
				if(ctor.getParameterTypes().length == recordEntries.length)
					ctorFound = true;

			if(!ctorFound)
				throw Util.runtimeException("Unexpected number of constructor arguments to " + recordClass.toString() + ": got " + recordEntries.length);

			ret = Reflector.invokeConstructor(recordClass, recordEntries);
			}
		else
			{

			IPersistentMap vals = RT.map(recordEntries);
			for(ISeq s = RT.keys(vals); s != null; s = s.next())
				{
				if(!(s.first() instanceof Keyword))
					throw Util.runtimeException("Unreadable defrecord form: key must be of type clojure.lang.Keyword, got " + s.first().toString());
				}
			ret = Reflector.invokeStaticMethod(recordClass, "create", new Object[]{vals});
			}

		return ret;
	}
}

/*
public static void main(String[] args) throws Exception{
	//RT.init();
	PushbackReader rdr = new PushbackReader( new java.io.StringReader( "(+ 21 21)" ) );
	Object input = LispReader.read(rdr, false, new Object(), false );
	System.out.println(Compiler.eval(input));
}

public static void main(String[] args){
	LineNumberingPushbackReader r = new LineNumberingPushbackReader(new InputStreamReader(System.in));
	OutputStreamWriter w = new OutputStreamWriter(System.out);
	Object ret = null;
	try
		{
		for(; ;)
			{
			ret = LispReader.read(r, true, null, false);
			RT.print(ret, w);
			w.write('\n');
			if(ret != null)
				w.write(ret.getClass().toString());
			w.write('\n');
			w.flush();
			}
		}
	catch(Exception e)
		{
		e.printStackTrace();
		}
}
 */

}

