/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Mar 25, 2006 11:42:47 AM */

package clojure.lang;

import java.io.Serializable;
import java.io.ObjectStreamException;


public class Symbol extends AFn implements IObj, Comparable, Named, Serializable, IHashEq{
//these must be interned strings!
final String ns;  //namespace
final String name; //名称
private int _hasheq;  //hash值缓存，在 PersistentMap 中用到，
                       //跟 clojure 1.6 的 hash 优化有关系，详情见http://dev.clojure.org/display/design/Better+hashing
final IPersistentMap _meta;  //元数据
String _str;  //toString方法缓存

public String toString(){
	//做了缓存
	if(_str == null){
		if(ns != null)
			//字符串同时做 intern，拘留到 JVM 常量池
			_str = (ns + "/" + name).intern();
		else
			_str = name;
	}
	return _str;
}

public String getNamespace(){
	return ns;
}

public String getName(){
	return name;
}

// the create thunks preserve binary compatibility with code compiled
// against earlier version of Clojure and can be removed (at some point).
static public Symbol create(String ns, String name) {
    return Symbol.intern(ns, name);
}

static public Symbol create(String nsname) {
    return Symbol.intern(nsname);
}
    
static public Symbol intern(String ns, String name){
	return new Symbol(ns == null ? null : ns.intern(), name.intern());
}

static public Symbol intern(String nsname){
	int i = nsname.indexOf('/');
	if(i == -1 || nsname.equals("/"))
		return new Symbol(null, nsname.intern());
	else
		//切分 namespace 和 name，这两个字符串也做了 intern
		return new Symbol(nsname.substring(0, i).intern(), nsname.substring(i + 1).intern());
}

//要求传入的 name 和 ns 都必须 intern 过，这个命名完全是 c 风格的啊
private Symbol(String ns_interned, String name_interned){
	this.name = name_interned;
	this.ns = ns_interned;
	this._meta = null;
}

public boolean equals(Object o){
	if(this == o)
		return true;
	if(!(o instanceof Symbol))
		return false;

	Symbol symbol = (Symbol) o;

	//因为 intern 过，所以可以直接用 == 比较
	//identity compares intended, names are interned
	return name == symbol.name && ns == symbol.ns;
}

public int hashCode(){
	return Util.hashCombine(name.hashCode(), Util.hash(ns));
}

public int hasheq() {
	if(_hasheq == 0){
		//计算并缓存 _hasheq
		_hasheq = Util.hashCombine(Murmur3.hashUnencodedChars(name), Util.hash(ns));
	}
	return _hasheq;
}

public IObj withMeta(IPersistentMap meta){
	return new Symbol(meta, ns, name);
}

private Symbol(IPersistentMap meta, String ns, String name){
	this.name = name;
	this.ns = ns;
	this._meta = meta;
}

public int compareTo(Object o){
	Symbol s = (Symbol) o;
	if(this.equals(o))
		return 0;
	if(this.ns == null && s.ns != null)
		return -1;
	if(this.ns != null)
		{
		if(s.ns == null)
			return 1;
		int nsc = this.ns.compareTo(s.ns);
		if(nsc != 0)
			return nsc;
		}
	return this.name.compareTo(s.name);
}

//处理java序列化，防止 ns 和 name 没有被intern，破坏 == 比较。
private Object readResolve() throws ObjectStreamException{
	return intern(ns, name);
}

//从obj中查找本symbol对应的value
public Object invoke(Object obj) {
	return RT.get(obj, this);
}

//同上，只不过在找不到的时候可以提供一个默认值
public Object invoke(Object obj, Object notFound) {
	return RT.get(obj, this, notFound);
}

public IPersistentMap meta(){
	return _meta;
}
}
