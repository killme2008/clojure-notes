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
//构造器 reader  #record[x y z]语法
static IFn ctorReader = new CtorReader();

static
	{
	//注册各种宏符号对应的 reader
	macros['"'] = new StringReader();  // " 开头的使用字符串Reader
	macros[';'] = new CommentReader();  // 注释
	macros['\''] = new WrappingReader(QUOTE); // quote 
	macros['@'] = new WrappingReader(DEREF);// deref符号@
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
/**
 * 读取一个 token
 * @param r
 * @param initch
 * @return
 */
static private String readToken(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		//如果是末尾、空白或者终止宏字符，那么退回字符并返回已经读取的字符串
		if(ch == -1 || isWhitespace(ch) || isTerminatingMacro(ch))
			{
			unread(r, ch);
			return sb.toString();
			}
		sb.append((char) ch);
		}
}
/**
 * 读取数字
 * @param r
 * @param initch
 * @return
 */
static private Object readNumber(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		//遇到末尾、空白和宏字符就结束读取，退回字符并跳出循环
		if(ch == -1 || isWhitespace(ch) || isMacro(ch))
			{
			unread(r, ch);
			break;
			}
		sb.append((char) ch);
		}

	String s = sb.toString();
	//尝试匹配数字
	Object n = matchNumber(s);
	if(n == null)
		throw new NumberFormatException("Invalid number: " + s);
	return n;
}
/**
 * 读取 Unicode 字符
 * @param token
 * @param offset
 * @param length
 * @param base
 * @return
 */
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
/**
 * 同样，读取 unicode 字符，相比 readUnicodeChar 的区别是从输入流中读取，而前者是从token中读取
 * @param r
 * @param initch
 * @param base
 * @param length
 * @param exact
 * @return
 */
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
/**
 * 解释读出来的 Token 是什么意思
 * @param s
 * @return
 */
static private Object interpretToken(String s) {
	//nil
	if(s.equals("nil"))
		{
		return null;
		}
	//true
	else if(s.equals("true"))
		{
		return RT.T;
		}
	//false
	else if(s.equals("false"))
		{
		return RT.F;
		}
	Object ret = null;
	//是否是symbol类型，尝试匹配
	ret = matchSymbol(s);
	if(ret != null)
		return ret;
	// 未知的任何东西
	throw Util.runtimeException("Invalid token: " + s);
}

/**
 * 匹配是否是合法的 symbol
 * @param s
 * @return
 */
private static Object matchSymbol(String s){
	//[:]?([\\D&&[^/]].*/)?(/|[\\D&&[^/]][^/]*)
	Matcher m = symbolPat.matcher(s);
	if(m.matches())
		{
		int gc = m.groupCount();
		String ns = m.group(1); //namespace, 匹配分组1 ([\\D&&[^/]].*/)
		String name = m.group(2); //symbol name,匹配分组2 (/|[\\D&&[^/]][^/]*)
		//过滤非法的 symbol，namespace以:/结尾，或者 name 以 : 结尾，或者除了首字符外包含 ::，都直接返回null，表示不匹配
		if(ns != null && ns.endsWith(":/")
		   || name.endsWith(":")
		   || s.indexOf("::", 1) != -1)
			return null;
		//如果以双冒号开始，例如::a，将转成 :{namespace}/a 形式的keyword
		//A keyword that begins with two colons is resolved in the current namespace
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
		//如果是keyword，以冒号开始
		boolean isKeyword = s.charAt(0) == ':';
		Symbol sym = Symbol.intern(s.substring(isKeyword ? 1 : 0));
		//转成keyword
		if(isKeyword)
			return Keyword.intern(sym);
		//否则返回 symbol
		return sym;
		}
	return null;
}

/**
 * 使用正则表达式匹配数字，捕获组
 * @param s
 * @return
 */
private static Object matchNumber(String s){
	Matcher m = intPat.matcher(s);
	//([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[0-9]+)(N)?
	//尝试匹配整数
	if(m.matches())
		{
		//如果第二组捕获，也就是匹配0
		if(m.group(2) != null)
			{
			//并且第八组也就是N匹配，返回BigInt的零值
			if(m.group(8) != null)
				return BigInt.ZERO;
			//否则返回普通数字0
			return Numbers.num(0);
			}
		//匹配正负符号
		boolean negate = (m.group(1).equals("-"));
		String n;
		int radix = 10;
		//如果第三组匹配，也就是 ([1-9][0-9]*)，表明是十进制
		if((n = m.group(3)) != null)
			radix = 10;
		//如果0[xX]([0-9A-Fa-f]+)匹配，表明是16进制
		else if((n = m.group(4)) != null)
			radix = 16;
		//如果0([0-7]+)匹配，表明是8进制
		else if((n = m.group(5)) != null)
			radix = 8;
		//如果([1-9][0-9]?)[rR]([0-9A-Za-z]+)匹配，表明是指定进制，例如2r10101，二进制
		else if((n = m.group(7)) != null)
			//读取进制，也就是([1-9][0-9]?)
			radix = Integer.parseInt(m.group(6));
		//不匹配，返回null
		if(n == null)
			return null;
		//尝试创建BigInteger
		BigInteger bn = new BigInteger(n, radix);
		//决定正负符号
		if(negate)
			bn = bn.negate();
		//如果结尾是N，返回 BigInt
		if(m.group(8) != null)
			return BigInt.fromBigInteger(bn);
		//如果位数少于64位，返回Long类型，否则提升到 BigInt
		return bn.bitLength() < 64 ?
		       Numbers.num(bn.longValue())
		                           : BigInt.fromBigInteger(bn);
		}
	//尝试匹配浮点数
	//([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?
	m = floatPat.matcher(s);
	if(m.matches())
		{
		//如果(M)? 存在，返回BigDecimal,分组1就是([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)
		if(m.group(4) != null)
			return new BigDecimal(m.group(1));
		//否则，返回 Double
		return Double.parseDouble(s);
		}
	//([-+]?[0-9]+)/([0-9]+)  匹配分数
	m = ratioPat.matcher(s);
	if(m.matches())
		{
		//获取分子
		String numerator = m.group(1);
		//如果有加号，去掉加号
		if (numerator.startsWith("+")) numerator = numerator.substring(1);
		//分子除以分母就是所谓分数
		return Numbers.divide(Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(numerator))),
		                      Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(m.group(2)))));
		}
	//不匹配任何数字类型，返回null
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
/**
 * 是否是宏字符
 * @param ch
 * @return
 */
static private boolean isMacro(int ch){
	return (ch < macros.length && macros[ch] != null);
}
/**	
 * 是否是终止宏字符
 * @param ch
 * @return
 */
static private boolean isTerminatingMacro(int ch){
	return (ch != '#' && ch != '\'' && ch != '%' && isMacro(ch));
}

/**
 * 正则表达式reader
 * @author dennis
 *
 */
public static class RegexReader extends AFn{
	static StringReader stringrdr = new StringReader();

	public Object invoke(Object reader, Object doublequote) {
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;
		//读到下一个双引号结束
		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			//无效的末尾
			if(ch == -1)
				throw Util.runtimeException("EOF while reading regex");
			sb.append( (char) ch );
			//忽略escape，因此不需要像java的正则那样双斜杠 escape
			if(ch == '\\')	//escape
				{
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading regex");
				sb.append( (char) ch ) ;
				}
			}
		//编译正则
		return Pattern.compile(sb.toString());
	}
}
/**
 * 字符串reader
 * @author dennis
 *
 */
public static class StringReader extends AFn{
	public Object invoke(Object reader, Object doublequote) {
		//结果字符串
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;
		//读取直到遇到结束的双引号
		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			//意外结束
			if(ch == -1)
				throw Util.runtimeException("EOF while reading string");
			//转义字符
			if(ch == '\\')	//escape
				{
				//读取下一个字符
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading string");
				//根据预读的字符判断是什么转义符号
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
						//  双反斜杠转义，也就是反斜杠
						break;
					case '"':
						// 双引号转义，也就是要使用双引号
						break;
					case 'b':
						ch = '\b';
						break;
					case 'f':
						ch = '\f';
						break;
					case 'u':
					{
						//unicode 字符 \u1234
					ch = read1(r);
					//非16进制，抛出异常
					if (Character.digit(ch, 16) == -1)
						throw Util.runtimeException("Invalid unicode escape: \\u" + (char) ch);
					ch = readUnicodeChar((PushbackReader) r, ch, 16, 4, true);
					break;
					}
					default:
					{
						//8进制转义字符，如\063 表示字符3
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
			//拼接到结果字符串
			sb.append((char) ch);
			}
		return sb.toString();
	}
}
/**
 * 注释 reader
 * @author dennis
 *
 */
public static class CommentReader extends AFn{
	public Object invoke(Object reader, Object semicolon) {
		Reader r = (Reader) reader;
		int ch;
		do
			{
			ch = read1(r);
			//一整行都是注释，忽略
			} while(ch != -1 && ch != '\n' && ch != '\r');
		return r;
	}

}
/**
 * 忽略 #_ 开始的对象
 * @author dennis
 *
 */
public static class DiscardReader extends AFn{
	public Object invoke(Object reader, Object underscore) {
		PushbackReader r = (PushbackReader) reader;
		read(r, true, null, true);
		return r;
	}
}
/**
 * 读取后的对象和传入的symbol组成 list，针对quote和deref符号，也就是'和@
 * @author dennis
 *
 */
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
/**
 * 废弃掉的WrappingReader
 * @author dennis
 *
 */
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
/**
 * #'x 的 var reader，返回 (var value) list
 * @author dennis
 *
 */
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

/**
 * #开头，转交给 dispatchMacros 表中的 fn 来处理
 * @author dennis
 *
 */
public static class DispatchReader extends AFn{
	//hash 是 # 符号
	public Object invoke(Object reader, Object hash) {
		//读取下一个字符，决定采用哪个reader fn
		int ch = read1((Reader) reader);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		IFn fn = dispatchMacros[ch];

		// Try the ctor reader first
		//参考http://clojure.org/reader:deftype, defrecord, and constructor calls (version 1.3 and later):
		//Calls to Java class, deftype, and defrecord constructors can be called using their fully qualified class name preceded by # and followed by a vector:
		if(fn == null) {
			//如果没有找到 dispatchMacro fn，尝试用 CtorReader
		unread((PushbackReader) reader, ch);
		Object result = ctorReader.invoke(reader, ch);

		if(result != null)
			return result;
		else
			throw Util.runtimeException(String.format("No dispatch macro for: %c", (char) ch));
		}
		
		//转交给 fn
		return fn.invoke(reader, ch);
	}
}

/**
 * 为匿名函数的参数产生唯一 symbol
 * @param n
 * @return
 */
static Symbol garg(int n){
	return Symbol.intern(null, (n == -1 ? "rest" : ("p" + n)) + "__" + RT.nextID() + "#");
}
/**
 * 匿名函数快速记法 reader
 * @author dennis
 *
 */
public static class FnReader extends AFn{
	public Object invoke(Object reader, Object lparen) {
		PushbackReader r = (PushbackReader) reader;
		//如果 ARG_ENV 已经存在，证明是嵌套的 #() 函数，抛出异常
		if(ARG_ENV.deref() != null)
			throw new IllegalStateException("Nested #()s are not allowed");
		try
			{
			//压入参数map，var名称固定为 ARG_ENV，临时使用，不用担心混淆,为了保证参数顺序，必须使用sorted-map
			Var.pushThreadBindings(
					RT.map(ARG_ENV, PersistentTreeMap.EMPTY));
			//退回左括号
			unread(r, '(');
			//将整个form读取出来,同时插入参数到 ARGV_ENV
			Object form = read(r, true, null, true);
			//参数列表
			PersistentVector args = PersistentVector.EMPTY;
			// %x 到 参数的映射map，保证顺序 % %1 %2
			PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
			//倒序参数
			ISeq rargs = argsyms.rseq();
			if(rargs != null)
				{
				//获取最大参数编号
				int higharg = (Integer) ((Map.Entry) rargs.first()).getKey();
				if(higharg > 0)
					//从1到higharg，一一关联到 args vector
					{
					for(int i = 1; i <= higharg; ++i)
						{
						Object sym = argsyms.valAt(i);
						if(sym == null)
							sym = garg(i);
						args = args.cons(sym);
						}
					}
				//存在可变参数 %&
				Object restsym = argsyms.valAt(-1);
				if(restsym != null)
					{
					//加入两个： & 和 参数名
					args = args.cons(Compiler._AMP_);
					args = args.cons(restsym);
					}
				}
			
			//返回一个 fn list 形如 (fn [arguments] form)
			return RT.list(Compiler.FN, args, form);
			}
		finally
			{
			//弹出 ARG_ENV
			Var.popThreadBindings();
			}
	}
}
/**
 * 注册匿名参数
 * @param n
 * @return
 */
static Symbol registerArg(int n){
	PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
	if(argsyms == null)
		{
		//不在快速记法内，抛出异常
		throw new IllegalStateException("arg literal not in #()");
		}
	//尝试获取，如果存在，直接返回
	Symbol ret = (Symbol) argsyms.valAt(n);
	//否则，产生一个唯一 symbol（类似gensym)，关联到n
	if(ret == null)
		{
		ret = garg(n);
		ARG_ENV.set(argsyms.assoc(n, ret));
		}
	return ret;
}
/**
 * 读取 % 快速记法参数
 * @author dennis
 *
 */
static class ArgReader extends AFn{
	public Object invoke(Object reader, Object pct) {
		PushbackReader r = (PushbackReader) reader;
		if(ARG_ENV.deref() == null)
			{
			//不在匿名函数快速记法范围内，尝试解释 %，比如你可以定义(def % 3)
			return interpretToken(readToken(r, '%'));
			}
		int ch = read1(r);
		unread(r, ch);
		//% alone is first arg
		//% 就是第一个参数
		if(ch == -1 || isWhitespace(ch) || isTerminatingMacro(ch))
			{
			//注册参数到 ARGV_ENV
			return registerArg(1);
			}
		Object n = read(r, true, null, true);
		//可变参数， %&，编号负数
		if(n.equals(Compiler._AMP_))
			return registerArg(-1);
		//非数字，非法参数
		if(!(n instanceof Number))
			throw new IllegalStateException("arg literal must be %, %& or %integer");
		//注册参数
		return registerArg(((Number) n).intValue());
	}
}
/**
 * 元数据 reader
 * @author dennis
 *
 */
public static class MetaReader extends AFn{
	public Object invoke(Object reader, Object caret) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		//获取行号和列号
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		//meta对象，可能是map，可能是symbol，也可能是字符串，例如(defn t [^"[B" bs] (String. bs))
		Object meta = read(r, true, null, true);
		//symbol 或者 字符串，就是简单的type hint tag
		if(meta instanceof Symbol || meta instanceof String)
			meta = RT.map(RT.TAG_KEY, meta);
		//如果是keyword，证明是布尔值的开关变量，如 ^:dynamic ^:private
		else if (meta instanceof Keyword)
			meta = RT.map(meta, RT.T);
		//如果连 map 都不是，那很抱歉，非法的meta数据
		else if(!(meta instanceof IPersistentMap))
			throw new IllegalArgumentException("Metadata must be Symbol,Keyword,String or Map");

		//读取要附加元数据的目标对象
		Object o = read(r, true, null, true);
		if(o instanceof IMeta)
			//如果可以附加，那么继续走下去
			{
			if(line != -1 && o instanceof ISeq)
				{
				//如果是ISeq，加入行号，列号，编译的时候可以行号写入字节码
				meta = ((IPersistentMap) meta).assoc(RT.LINE_KEY, line).assoc(RT.COLUMN_KEY, column);
				}
			if(o instanceof IReference)
				{
				//如果是 ref，重设 meta
				((IReference)o).resetMeta((IPersistentMap) meta);
				return o;
				}
			//增加 meta 到原有的 ometa
			Object ometa = RT.meta(o);
			for(ISeq s = RT.seq(meta); s != null; s = s.next()) {
			IMapEntry kv = (IMapEntry) s.first();
			ometa = RT.assoc(ometa, kv.getKey(), kv.getValue());
			}
			//关联到o
			return ((IObj) o).withMeta((IPersistentMap) ometa);
			}
		else
			//不可附加元素，抱歉，直接抛出异常
			throw new IllegalArgumentException("Metadata can only be applied to IMetas");
	}

}
/**
 * syntax ` quote reader 
 * @author dennis
 *
 */
public static class SyntaxQuoteReader extends AFn{
	public Object invoke(Object reader, Object backquote) {
		PushbackReader r = (PushbackReader) reader;
		try
			{
			//压入 GENSYM_ENV
			Var.pushThreadBindings(
					RT.map(GENSYM_ENV, PersistentHashMap.EMPTY));

			Object form = read(r, true, null, true);
			//对读出来的form，如果有 a# 变量，做下 gensym 替代
			return syntaxQuote(form);
			}
		finally
			{
			//弹出 GENSYM_ENV
			Var.popThreadBindings();
			}
	}

	static Object syntaxQuote(Object form) {
		Object ret;
		//如果 form 本身是 special form，直接返回 quote list
		if(Compiler.isSpecial(form))
			ret = RT.list(Compiler.QUOTE, form);
		else if(form instanceof Symbol)
			//如果 form 是一个 symbol
			{
			Symbol sym = (Symbol) form;
			//以#结尾并且没有 ns， generated symbol 
			if(sym.ns == null && sym.name.endsWith("#"))
				{
				IPersistentMap gmap = (IPersistentMap) GENSYM_ENV.deref();
				//不在 syntax quote范围内，抛出异常
				if(gmap == null)
					throw new IllegalStateException("Gensym literal not in syntax-quote");
				//看下是不是已经存在了，不存在，关联进去
				Symbol gs = (Symbol) gmap.valAt(sym);
				if(gs == null)
					GENSYM_ENV.set(gmap.assoc(sym, gs = Symbol.intern(null,
							//可以看到 gensym 的规则 name 去掉#结尾，然后加上 __{id}__auto__
					                                                  sym.name.substring(0, sym.name.length() - 1)
					                                                  + "__" + RT.nextID() + "__auto__")));
				//替换sym为实际产生的symbol
				sym = gs;
				}
			//以.结尾，应该是 class,record,type之类构造器
			else if(sym.ns == null && sym.name.endsWith("."))
				{
				//移除末尾的.
				Symbol csym = Symbol.intern(null, sym.name.substring(0, sym.name.length() - 1));
				//尝试resolve解析，变成qulified symbol
				csym = Compiler.resolveSymbol(csym);
				//返回解析后的全限定 symbol
				sym = Symbol.intern(null, csym.name.concat("."));
				}
			else if(sym.ns == null && sym.name.startsWith("."))
				{
				// Simply quote method names. 
				//方法名称，如 .toString
				}
			else
				{
				//ns 可能是类名
				Object maybeClass = null;
				if(sym.ns != null)
					maybeClass = Compiler.currentNS().getMapping(
							Symbol.intern(null, sym.ns));
				if(maybeClass instanceof Class)
					{
					// Classname/foo -> package.qualified.Classname/foo
					// Classname/foo  转成 package.qualified.Classname/foo
					sym = Symbol.intern(
							((Class)maybeClass).getName(), sym.name);
					}
				else
					//解析成全限定名
					sym = Compiler.resolveSymbol(sym);
				}
			ret = RT.list(Compiler.QUOTE, sym);
			}
		//如果是unqoute form ~x，取x
		else if(isUnquote(form))
			return RT.second(form);
		//如果是 ~@x，x不是list，抛出异常，正确应该是 ~@(x y z)
		else if(isUnquoteSplicing(form))
			throw new IllegalStateException("splice not in list");
		else if(form instanceof IPersistentCollection)
			{
			//如果form是集合，分情况分析
			
			//1.如果是record，直接返回
			if(form instanceof IRecord)
				ret = form;
			//2.如果是map，平铺成一层，转成(apply hash-map (seq (concat x y z ...)))
			// keyvals 递归展开，下同
			else if(form instanceof IPersistentMap)
				{
				IPersistentVector keyvals = flattenMap(form);
				ret = RT.list(APPLY, HASHMAP, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(keyvals.seq()))));
				}
			//3.如果是vector，转成 (apply vector (seq (concat ......)))
			else if(form instanceof IPersistentVector)
				{
				ret = RT.list(APPLY, VECTOR, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(((IPersistentVector) form).seq()))));
				}
			//4.如果是set，转成(apply hash-set (seq (concat ......)))
			else if(form instanceof IPersistentSet)
				{
				ret = RT.list(APPLY, HASHSET, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(((IPersistentSet) form).seq()))));
				}
			//如果是seq或list,直接(seq (concat ......))
			else if(form instanceof ISeq || form instanceof IPersistentList)
				{
				ISeq seq = RT.seq(form);
				//特殊处理空串 (list)
				if(seq == null)
					ret = RT.cons(LIST,null);
				else
					ret = RT.list(SEQ, RT.cons(CONCAT, sqExpandList(seq)));
				}
			else
				//不知道什么集合类型
				throw new UnsupportedOperationException("Unknown Collection type");
			}
		//如果form是一些"常量"，结果就是这些常量，例如`3=3
		else if(form instanceof Keyword
		        || form instanceof Number
		        || form instanceof Character
		        || form instanceof String)
			ret = form;
		else
			//否则，转成 quote form，例如 `#"test" 正则表达式
			ret = RT.list(Compiler.QUOTE, form);
		//如果有元数据，做下传递
		if(form instanceof IObj && RT.meta(form) != null)
			{
			//filter line and column numbers
			//移除line和column，然后关联到ret，防止覆盖新的ret中的行号和列号
			IPersistentMap newMeta = ((IObj) form).meta().without(RT.LINE_KEY).without(RT.COLUMN_KEY);
			if(newMeta.count() > 0)
				return RT.list(WITH_META, ret, syntaxQuote(((IObj) form).meta()));
			}
		return ret;
	}

	/**
	 * 如果form是集合，展开，递归处理
	 * @param seq
	 * @return
	 */
	private static ISeq sqExpandList(ISeq seq) {
		//注意结果是 ISeq
		PersistentVector ret = PersistentVector.EMPTY;
		for(; seq != null; seq = seq.next())
			{
			Object item = seq.first();
			//unqote form，转成list，如(~3) 变成 (3)
			if(isUnquote(item))
				ret = ret.cons(RT.list(LIST, RT.second(item)));
			//同样 (~@x) 变成(x)，其中x展开拼接
			else if(isUnquoteSplicing(item))
				ret = ret.cons(RT.second(item));
			else
				//递归 syntaxQuote
				ret = ret.cons(RT.list(LIST, syntaxQuote(item)));
			}
		return ret.seq();
	}

	/**
	 * 展开map成vector，flattern一层
	 * @param form
	 * @return
	 */
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
/**
 * 判断是不是 unquote splicing
 * @param form
 * @return
 */
static boolean isUnquoteSplicing(Object form){
	return form instanceof ISeq && Util.equals(RT.first(form),UNQUOTE_SPLICING);
}
/**
 * 判断是否是 unquote
 * @param form
 * @return
 */
static boolean isUnquote(Object form){
	return form instanceof ISeq && Util.equals(RT.first(form),UNQUOTE);
}
/**
 * ~x或者~@x reader
 * @author dennis
 *
 */
static class UnquoteReader extends AFn{
	public Object invoke(Object reader, Object comma) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		//如果是~@
		if(ch == '@')
			{
			Object o = read(r, true, null, true);
			//转成(~@ result)
			return RT.list(UNQUOTE_SPLICING, o);
			}
		else
			{
			//退回ch
			unread(r, ch);
			Object o = read(r, true, null, true);
			//否则就是 ~x
			return RT.list(UNQUOTE, o);
			}
	}

}
/**
 * 字符读取器
 * @author dennis
 *
 */
public static class CharacterReader extends AFn{
	public Object invoke(Object reader, Object backslash) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		String token = readToken(r, (char) ch);
		//1.单字符
		if(token.length() == 1)
			return Character.valueOf(token.charAt(0));
		// \newline，各种转义符号
		else if(token.equals("newline"))
			return '\n';
		// \space
		else if(token.equals("space"))
			return ' ';
		// \tab
		else if(token.equals("tab"))
			return '\t';
		// \backspace
		else if(token.equals("backspace"))
			return '\b';
		else if(token.equals("formfeed"))
			return '\f';
		else if(token.equals("return"))
			return '\r';
		// unicode
		else if(token.startsWith("u"))
			{
			char c = (char) readUnicodeChar(token, 1, 4, 16);
			// http://blog.chinaunix.net/uid-10468429-id-2953054.html
			/**
			 * 3.2.6 代理（Surrogates） 
D25 高代理码点（high-surrogate code point）：位于范围U+D800到U+DBFF内的Unicode码点。 
D25a 高代理编码单元（high-surrogate code unit）：在范围D800到DBFF内的16位编码单元，作为UTF-16中代理对的起始编码单元。 
D26 低代理码点（low-surrogate code point）：位于范围U+DC00到U+DFFF内的Unicode码点。 
D26a 低代理编码单元（low-surrogate code unit）：在范围DC00到DFFF内的16位编码单元，作为UTF-16中代理对的结尾编码单元。 
D27 代理对（surrogate pair）：由两个16位编码单元组成的序列来表示单个的抽象字符，其中，代理对的第一部分为高代理编码单元，第二部分为低代理编码单元。 
			 */
			if(c >= '\uD800' && c <= '\uDFFF') // surrogate code unit?
				throw Util.runtimeException("Invalid character constant: \\u" + Integer.toString(c, 16));
			return c;
			}
		else if(token.startsWith("o"))
			{
			//8进制字符 \o063
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
/**
 * List  reader (x y z)
 * @author dennis
 *
 */
public static class ListReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		//sequence 都需要获取行号，因为很可能是代码form
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		//读取list
		List list = readDelimitedList(')', r, true);
		//Null object模式
		if(list.isEmpty())
			return PersistentList.EMPTY;
		//转化成PersistentList
		IObj s = (IObj) PersistentList.create(list);
//		IObj s = (IObj) RT.seq(list);
		//记录行列信息到 meta
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
/**
 * #=java.lang.String , #=(+ 1 2) 之类的 eval reader
 * @author dennis
 *
 */
public static class EvalReader extends AFn{
	public Object invoke(Object reader, Object eq) {
		if (!RT.booleanCast(RT.READEVAL.deref()))
			{
			//尊重 *read-eval* 选项
			throw Util.runtimeException("EvalReader not allowed when *read-eval* is false.");
			}

		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
		if(o instanceof Symbol)
			{
			//如果是symbol，当成类名
			return RT.classForName(o.toString());
			}
		//如果是 list
		else if(o instanceof IPersistentList)
			{
			Symbol fs = (Symbol) RT.first(o);
			//1.(var xxx)形式
			if(fs.equals(THE_VAR))
				{
				//全限定var,这里可能NPE，提了个 ticket http://dev.clojure.org/jira/browse/CLJ-1507
				Symbol vs = (Symbol) RT.second(o);
				return RT.var(vs.ns, vs.name);  //Compiler.resolve((Symbol) RT.second(o),true);
				}
			//2. Constructor.
			if(fs.name.endsWith("."))
				{
				//next得到参数列表
				Object[] args = RT.toArray(RT.next(o));
				//调用构造器
				return Reflector.invokeConstructor(RT.classForName(fs.name.substring(0, fs.name.length() - 1)), args);
				}
			//3.静态成员 A/a
			if(Compiler.namesStaticMember(fs))
				{
				Object[] args = RT.toArray(RT.next(o));
				return Reflector.invokeStaticMethod(fs.ns, fs.name, args);
				}
			//4.或者尝试解析成当前 ns var，因为在list里，必然是IFN
			Object v = Compiler.maybeResolveIn(Compiler.currentNS(), fs);
			if(v instanceof Var)
				{
				//强制转成IFN，并调用参数
				//例如 (defn x [a] (println a)) #=(x 3)
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
/**
 * Vector 读取器
 * @author dennis
 *
 */
public static class VectorReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		//读出List，然后创建lazy vector
		return LazilyPersistentVector.create(readDelimitedList(']', r, true));
	}

}

/**
 * clojure map reader，从这里可以看出，list是根本，其他数据类型都是先按照list读取，然后转成clojure数据结构
 * @author dennis
 *
 */
public static class MapReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		//仍然是读出List，变成数组
		Object[] a = readDelimitedList('}', r, true).toArray();
		if((a.length & 1) == 1)
			throw Util.runtimeException("Map literal must contain an even number of forms");
		//调用map来构造
		return RT.map(a);
	}

}
/**
 * hash-set reader
 * @author dennis
 *
 */
public static class SetReader extends AFn{
	public Object invoke(Object reader, Object leftbracket) {
		PushbackReader r = (PushbackReader) reader;
		//一样
		return PersistentHashSet.createWithCheck(readDelimitedList('}', r, true));
	}

}
/**
 * 不匹配reader，简单抛出异常
 * @author dennis
 *
 */
public static class UnmatchedDelimiterReader extends AFn{
	public Object invoke(Object reader, Object rightdelim) {
		throw Util.runtimeException("Unmatched delimiter: " + rightdelim);
	}

}
/**
 * 无法读取的form，简单抛出异常, #<
 * @author dennis
 *
 */
public static class UnreadableReader extends AFn{
	public Object invoke(Object reader, Object leftangle) {
		throw Util.runtimeException("Unreadable form");
	}
}
/**
 * 读取list到分割符号 delim为止
 * @param delim
 * @param r
 * @param isRecursive
 * @return
 */
public static List readDelimitedList(char delim, PushbackReader r, boolean isRecursive) {
	final int firstline =
			(r instanceof LineNumberingPushbackReader) ?
			((LineNumberingPushbackReader) r).getLineNumber() : -1;
    //收集结果
	ArrayList a = new ArrayList();

	for(; ;)
		{
		int ch = read1(r);
		//忽略空白
		while(isWhitespace(ch))
			ch = read1(r);
		//非法终止
		if(ch == -1)
			{
			if(firstline < 0)
				throw Util.runtimeException("EOF while reading");
			else
				throw Util.runtimeException("EOF while reading, starting at line " + firstline);
			}
		//读到终止符号，也就是右括号)，停止
		if(ch == delim)
			break;
		//可能是macro fn
		IFn macroFn = getMacro(ch);
		if(macroFn != null)
			{
			Object mret = macroFn.invoke(r, (char) ch);
			//no op macros return the reader
			
			//macro fn 如果是no op，返回reader本身
			if(mret != r)
				//非no op,加入结果集合
				a.add(mret);
			}
		else
			{
			//非macro，回退ch
			unread(r, ch);
			//读取object并加入结果集合
			Object o = read(r, true, null, isRecursive);
			//同样，根据约定，如果返回是r，表示null
			if(o != r)
				a.add(o);
			}
		}
	//返回收集的结果集合

	return a;
}
/**
 * 构造器 reader
 * @author dennis
 *
 */
public static class CtorReader extends AFn{
	public Object invoke(Object reader, Object firstChar){
		PushbackReader r = (PushbackReader) reader;
		Object name = read(r, true, null, false);
		//tag 必须是 symbol
		if (!(name instanceof Symbol))
			throw new RuntimeException("Reader tag must be a symbol");
		Symbol sym = (Symbol)name;
		//如果包含.，这认为是record，否则就是tagged数据
		return sym.getName().contains(".") ? readRecord(r, sym) : readTagged(r, sym);
	}

	/**
	 * 读取 tagged 对象
	 * @param reader
	 * @param tag
	 * @return
	 */
	private Object readTagged(PushbackReader reader, Symbol tag){
		//读取下一个对象
		Object o = read(reader, true, null, true);
		//取 *data-readers*
		ILookup data_readers = (ILookup)RT.DATA_READERS.deref();
		//根据tag找到相应的 reader
		IFn data_reader = (IFn)RT.get(data_readers, tag);
		if(data_reader == null){
			//没有找到，从default-data-readers查找
			// default-data-readers包含：#uuid "5231b533-ba17-4787-98a3-f2df37de2aD7" 和 #inst "2014-08-16T18:00:59.519-00:00"
		data_readers = (ILookup)RT.DEFAULT_DATA_READERS.deref();
		data_reader = (IFn)RT.get(data_readers, tag);
		//如果 default-data-readers 也没有，尝试使用 *default-data-reader-fn* 
		if(data_reader == null){
		IFn default_reader = (IFn)RT.DEFAULT_DATA_READER_FN.deref();
		if(default_reader != null)
			//有则调用
			return default_reader.invoke(tag, o);
		else
			//没有，无法解析
			throw new RuntimeException("No reader function for tag " + tag.toString());
		}
		}

		return data_reader.invoke(o);
	}

	/**
	 * 读取 record
	 * @param r
	 * @param recordName
	 * @return
	 */
	private Object readRecord(PushbackReader r, Symbol recordName){
        boolean readeval = RT.booleanCast(RT.READEVAL.deref());
        //检查 *read-eval* 值
	    if(!readeval)
		    {
		    throw Util.runtimeException("Record construction syntax can only be used when *read-eval* == true");
		    }

	    //load class
		Class recordClass = RT.classForNameNonLoading(recordName.toString());

		char endch;
		//是否是 [x y z]形式
		boolean shortForm = true;
		int ch = read1(r);

		// flush whitespace
		//忽略空白
		while(isWhitespace(ch))
			ch = read1(r);

		// A defrecord ctor can take two forms. Check for map->R version first.
		//如果是{}形式, shortForm = false;
		if(ch == '{')
			{
			endch = '}';
			shortForm = false;
			}
		//否则是 vector形式了
		else if (ch == '[')
			endch = ']';
		else
			throw Util.runtimeException("Unreadable constructor form starting with \"#" + recordName + (char) ch + "\"");

		//读取参数列表
		Object[] recordEntries = readDelimitedList(endch, r, true).toArray();
		Object ret = null;
		//获取所有构造函数
		Constructor[] allctors = ((Class)recordClass).getConstructors();

		//迭代构造函数，找到最匹配的
		if(shortForm)
			{
			boolean ctorFound = false;
			for (Constructor ctor : allctors)
				//尝试查找一个匹配的函数，没有break?当然没有。。clojure动态类型,record也是参数个数重载
				if(ctor.getParameterTypes().length == recordEntries.length)
					ctorFound = true;

			if(!ctorFound)
				throw Util.runtimeException("Unexpected number of constructor arguments to " + recordClass.toString() + ": got " + recordEntries.length);

			ret = Reflector.invokeConstructor(recordClass, recordEntries);
			}
		else
			{
			//map形式，调用 record 的静态 create 工厂方法
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

