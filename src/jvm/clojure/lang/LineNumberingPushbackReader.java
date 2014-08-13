/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package clojure.lang;

import java.io.PushbackReader;
import java.io.Reader;
import java.io.LineNumberReader;
import java.io.IOException;

/**
 * 继承 java.io.PushbackReader，内部包装了 java.io.LineNumberReader
 * 
 * @author dennis
 *
 */
public class LineNumberingPushbackReader extends PushbackReader{

// This class is a PushbackReader that wraps a LineNumberReader. The code
// here to handle line terminators only mentions '\n' because
// LineNumberReader collapses all occurrences of CR, LF, and CRLF into a
// single '\n'.

private static final int newline = (int) '\n';

private boolean _atLineStart = true; //是否在行首
private boolean _prev;  //上一次_atLineStart值
private int _columnNumber = 1;

public LineNumberingPushbackReader(Reader r){
	super(new LineNumberReader(r));
}

public LineNumberingPushbackReader(Reader r, int size){
	super(new LineNumberReader(r, size));
}

/**
 * 获取行号
 * @return
 */
public int getLineNumber(){
	return ((LineNumberReader) in).getLineNumber() + 1;
}
/**
 * 获取列号
 * @return
 */
public int getColumnNumber(){
	return _columnNumber;
}

/**
 * 读取下一个字符
 */
public int read() throws IOException{
    int c = super.read();
    //记录上一次读取后的 _atLineStart 值
    _prev = _atLineStart;
    if((c == newline) || (c == -1))
        {
    	//换行，重设 _columnNumber 和 _atLineStart
        _atLineStart = true;
        _columnNumber = 1;
        }
    else
        {
    	//非换行，设置 _atLineStart 为 false
        _atLineStart = false;
        _columnNumber++;
        }
    return c;
}
/**
 * 将读取的字符塞回缓冲区，这里回退应该不能是换行号，否则 _columnNumber 变成0
 */
public void unread(int c) throws IOException{
    super.unread(c);
    //回退 _atLineStart 和 _columnNumber
    _atLineStart = _prev;
    _columnNumber--;
}

/**
 * 读取一行
 * @return
 * @throws IOException
 */
public String readLine() throws IOException{
    int c = read();
    String line;
    switch (c) {
    case -1:
    	//结尾
        line = null;
        break;
    case newline:
    	//遇到换行符，返回空行
        line = "";
        break;
    default:
    	//读取一行，这个效率感觉不咋样啊。
        String first = String.valueOf((char) c);
        String rest = ((LineNumberReader)in).readLine();
        line = (rest == null) ? first : first + rest;
        _prev = false;
        _atLineStart = true;
        _columnNumber = 1;
        break;
    }
    return line;
}
/**
 * 返回是否在行首
 * @return
 */
public boolean atLineStart(){
    return _atLineStart;
}
}
