package clojure.lang;

/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 */
/**
 * vector 支持
 * 1. 可以 assoc ,contains 判断
 * 2. 是 Sequential
 * 3. 是 stack
 * 4. 可以 reverse。
 * 5. 有索引的，随机访问.
 * @author dennis
 *
 */
public interface IPersistentVector extends Associative, Sequential, IPersistentStack, Reversible, Indexed{
int length();

IPersistentVector assocN(int i, Object val);

IPersistentVector cons(Object o);

}
