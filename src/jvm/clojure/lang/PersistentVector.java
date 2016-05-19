/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 5, 2007 */

package clojure.lang;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class PersistentVector extends APersistentVector implements IObj, IEditableCollection{

/**
 * 一个『大』节点，每个节点包含 32 个元素。
 * @author dennis
 *
 */
public static class Node implements Serializable {
	//表示创建或者修改的线程
	transient public final AtomicReference<Thread> edit;
	//容器，持有元素。
	public final Object[] array;

	public Node(AtomicReference<Thread> edit, Object[] array){
		this.edit = edit;
		this.array = array;
	}

	Node(AtomicReference<Thread> edit){
		this.edit = edit;
		this.array = new Object[32];
	}
}
/**
 * 空节点常量，用于空集合
 */
final static AtomicReference<Thread> NOEDIT = new AtomicReference<Thread>(null);
public final static Node EMPTY_NODE = new Node(NOEDIT, new Object[32]);
//总数大小
final int cnt;
//位移
public final int shift;
//根节点
public final Node root;
//末节点，当前正在做插入的『大节点』。
public final Object[] tail;
//元信息
final IPersistentMap _meta;

//空集合常量
public final static PersistentVector EMPTY = new PersistentVector(0, 5, EMPTY_NODE, new Object[]{});

//下面三个方法，分别从 Seq、List 和数组创建 vector，都是利用 TransientVector 的可变性来提升效率。
static public PersistentVector create(ISeq items){
	TransientVector ret = EMPTY.asTransient();
	for(; items != null; items = items.next())
		ret = ret.conj(items.first());
	return ret.persistent();
}

static public PersistentVector create(List items){
	TransientVector ret = EMPTY.asTransient();
	for(Object item : items)
		ret = ret.conj(item);
	return ret.persistent();
}

static public PersistentVector create(Object... items){
	TransientVector ret = EMPTY.asTransient();
	for(Object item : items)
		ret = ret.conj(item);
	return ret.persistent();
}

PersistentVector(int cnt, int shift, Node root, Object[] tail){
	this._meta = null;
	this.cnt = cnt;
	this.shift = shift;
	this.root = root;
	this.tail = tail;
}


PersistentVector(IPersistentMap meta, int cnt, int shift, Node root, Object[] tail){
	this._meta = meta;
	this.cnt = cnt;
	this.shift = shift;
	this.root = root;
	this.tail = tail;
}

public TransientVector asTransient(){
	return new TransientVector(this);
}
/**
 * 求出『末尾』位置坐标，等价于 cnt/32*32
 * @return
 */
final int tailoff(){
	//小于32，直接短路返回0，加速计算。
	if(cnt < 32)
		return 0;
	return ((cnt - 1) >>> 5) << 5;
}

//获取 index 所在『大』节点
public Object[] arrayFor(int i){
	if(i >= 0 && i < cnt)
		{
		//在末尾位置之外，直接返回 tail 节点。
		if(i >= tailoff())
			return tail;
		//否则，从 root 向下逐层定位。
		Node node = root;
		//每隔 5 位一层。
		for(int level = shift; level > 0; level -= 5)
			node = (Node) node.array[(i >>> level) & 0x01f];
		return node.array;
		}
	throw new IndexOutOfBoundsException();
}

public Object nth(int i){
	Object[] node = arrayFor(i);
	return node[i & 0x01f];
}

public Object nth(int i, Object notFound){
	if(i >= 0 && i < cnt)
		return nth(i);
	return notFound;
}

public PersistentVector assocN(int i, Object val){
	if(i >= 0 && i < cnt)
		{
		//末尾位置，直接加入 tail
		if(i >= tailoff())
			{
			Object[] newTail = new Object[tail.length];
			System.arraycopy(tail, 0, newTail, 0, tail.length);
			newTail[i & 0x01f] = val;

			return new PersistentVector(meta(), cnt, shift, root, newTail);
			}
		//查找到对应节点再加入
		return new PersistentVector(meta(), cnt, shift, doAssoc(shift, root, i, val), tail);
		}
	//如果位置超过 cnt，那么就走 cons，新插入节点
	if(i == cnt)
		return cons(val);
	throw new IndexOutOfBoundsException();
}

private static Node doAssoc(int level, Node node, int i, Object val){
	//递归插入，基本是 arrayFor的反过程。
	//注意，这里 clone，为了拷贝路径
	Node ret = new Node(node.edit,node.array.clone());
	//终止点，level 等于 0
	if(level == 0)
		{
		ret.array[i & 0x01f] = val;
		}
	else
		{
		//取 index 在这一层的 hash 值
		int subidx = (i >>> level) & 0x01f;
		//递归点
		ret.array[subidx] = doAssoc(level - 5, (Node) node.array[subidx], i, val);
		}
	return ret;
}

public int count(){
	return cnt;
}

public PersistentVector withMeta(IPersistentMap meta){
	return new PersistentVector(meta, cnt, shift, root, tail);
}

public IPersistentMap meta(){
	return _meta;
}


public PersistentVector cons(Object val){
	int i = cnt;
	//room in tail?
//	if(tail.length < 32)
	//末尾节点还有空间，直接插入 tail
	if(cnt - tailoff() < 32)
		{
		Object[] newTail = new Object[tail.length + 1];
		System.arraycopy(tail, 0, newTail, 0, tail.length);
		newTail[tail.length] = val;
		return new PersistentVector(meta(), cnt + 1, shift, root, newTail);
		}
	//full tail, push into tree
	//tail 满了
	Node newroot;
	Node tailnode = new Node(root.edit,tail);
	int newshift = shift;
	//overflow root?
	//root 没有位置了
	if((cnt >>> 5) > (1 << shift))
		{
		newroot = new Node(root.edit);
		newroot.array[0] = root;
		newroot.array[1] = newPath(root.edit,shift, tailnode);
		//递增层级
		newshift += 5;
		}
	else
		//否则，加入 root 
		newroot = pushTail(shift, root, tailnode);
	return new PersistentVector(meta(), cnt + 1, newshift, newroot, new Object[]{val});
}

//copy path
private Node pushTail(int level, Node parent, Node tailnode){
	//if parent is leaf, insert node,
	// else does it map to an existing child? -> nodeToInsert = pushNode one more level
	// else alloc new path
	//return  nodeToInsert placed in copy of parent
	int subidx = ((cnt - 1) >>> level) & 0x01f;
	Node ret = new Node(parent.edit, parent.array.clone());
	Node nodeToInsert;
	//第一层
	if(level == 5)
		{
		nodeToInsert = tailnode;
		}
	else
		{
		Node child = (Node) parent.array[subidx];
		nodeToInsert = (child != null)?
		                pushTail(level-5,child, tailnode)
		                :newPath(root.edit,level-5, tailnode);
		}
	ret.array[subidx] = nodeToInsert;
	return ret;
}

private static Node newPath(AtomicReference<Thread> edit,int level, Node node){
	if(level == 0)
		return node;
	
	Node ret = new Node(edit);
	
	ret.array[0] = newPath(edit, level - 5, node);
	return ret;
}

public IChunkedSeq chunkedSeq(){
	if(count() == 0)
		return null;
	return new ChunkedSeq(this,0,0);
}

public ISeq seq(){
	return chunkedSeq();
}

@Override
Iterator rangedIterator(final int start, final int end){
	return new Iterator(){
		int i = start;
		int base = i - (i%32);
		Object[] array = (start < count())?arrayFor(i):null;

		public boolean hasNext(){
			return i < end;
			}

		public Object next(){
			if(i-base == 32){
				array = arrayFor(i);
				base += 32;
				}
			return array[i++ & 0x01f];
			}

		public void remove(){
			throw new UnsupportedOperationException();
		}
	};
}

public Iterator iterator(){return rangedIterator(0,count());}

public Object kvreduce(IFn f, Object init){
    int step = 0;
    for(int i=0;i<cnt;i+=step){
        Object[] array = arrayFor(i);
        for(int j =0;j<array.length;++j){
            init = f.invoke(init,j+i,array[j]);
            if(RT.isReduced(init))
	            return ((IDeref)init).deref();
            }
        step = array.length;
    }
    return init;
}

static public final class ChunkedSeq extends ASeq implements IChunkedSeq,Counted{

	public final PersistentVector vec;
	final Object[] node;
	final int i;
	public final int offset;

	public ChunkedSeq(PersistentVector vec, int i, int offset){
		this.vec = vec;
		this.i = i;
		this.offset = offset;
		this.node = vec.arrayFor(i);
	}

	ChunkedSeq(IPersistentMap meta, PersistentVector vec, Object[] node, int i, int offset){
		super(meta);
		this.vec = vec;
		this.node = node;
		this.i = i;
		this.offset = offset;
	}

	ChunkedSeq(PersistentVector vec, Object[] node, int i, int offset){
		this.vec = vec;
		this.node = node;
		this.i = i;
		this.offset = offset;
	}

	public IChunk chunkedFirst() {
		return new ArrayChunk(node, offset);
		}

	public ISeq chunkedNext(){
		if(i + node.length < vec.cnt)
			return new ChunkedSeq(vec,i+ node.length,0);
		return null;
		}

	public ISeq chunkedMore(){
		ISeq s = chunkedNext();
		if(s == null)
			return PersistentList.EMPTY;
		return s;
	}

	public Obj withMeta(IPersistentMap meta){
		if(meta == this._meta)
			return this;
		return new ChunkedSeq(meta, vec, node, i, offset);
	}

	public Object first(){
		return node[offset];
	}

	public ISeq next(){
		if(offset + 1 < node.length)
			return new ChunkedSeq(vec, node, i, offset + 1);
		return chunkedNext();
	}

	public int count(){
		return vec.cnt - (i + offset);
	}
}

public IPersistentCollection empty(){
	return EMPTY.withMeta(meta());
}

//private Node pushTail(int level, Node node, Object[] tailNode, Box expansion){
//	Object newchild;
//	if(level == 0)
//		{
//		newchild = tailNode;
//		}
//	else
//		{
//		newchild = pushTail(level - 5, (Object[]) arr[arr.length - 1], tailNode, expansion);
//		if(expansion.val == null)
//			{
//			Object[] ret = arr.clone();
//			ret[arr.length - 1] = newchild;
//			return ret;
//			}
//		else
//			newchild = expansion.val;
//		}
//	//expansion
//	if(arr.length == 32)
//		{
//		expansion.val = new Object[]{newchild};
//		return arr;
//		}
//	Object[] ret = new Object[arr.length + 1];
//	System.arraycopy(arr, 0, ret, 0, arr.length);
//	ret[arr.length] = newchild;
//	expansion.val = null;
//	return ret;
//}

public PersistentVector pop(){
	if(cnt == 0)
		throw new IllegalStateException("Can't pop empty vector");
	if(cnt == 1)
		return EMPTY.withMeta(meta());
	//if(tail.length > 1)
	if(cnt-tailoff() > 1)
		{
		Object[] newTail = new Object[tail.length - 1];
		System.arraycopy(tail, 0, newTail, 0, newTail.length);
		return new PersistentVector(meta(), cnt - 1, shift, root, newTail);
		}
	Object[] newtail = arrayFor(cnt - 2);

	Node newroot = popTail(shift, root);
	int newshift = shift;
	if(newroot == null)
		{
		newroot = EMPTY_NODE;
		}
	if(shift > 5 && newroot.array[1] == null)
		{
		newroot = (Node) newroot.array[0];
		newshift -= 5;
		}
	return new PersistentVector(meta(), cnt - 1, newshift, newroot, newtail);
}

private Node popTail(int level, Node node){
	int subidx = ((cnt-2) >>> level) & 0x01f;
	if(level > 5)
		{
		Node newchild = popTail(level - 5, (Node) node.array[subidx]);
		if(newchild == null && subidx == 0)
			return null;
		else
			{
			Node ret = new Node(root.edit, node.array.clone());
			ret.array[subidx] = newchild;
			return ret;
			}
		}
	else if(subidx == 0)
		return null;
	else
		{
		Node ret = new Node(root.edit, node.array.clone());
		ret.array[subidx] = null;
		return ret;
		}
}

static final class TransientVector extends AFn implements ITransientVector, Counted{
	int cnt;
	int shift;
	Node root;
	Object[] tail;

	TransientVector(int cnt, int shift, Node root, Object[] tail){
		this.cnt = cnt;
		this.shift = shift;
		this.root = root;
		this.tail = tail;
	}

	TransientVector(PersistentVector v){
		this(v.cnt, v.shift, editableRoot(v.root), editableTail(v.tail));
	}

	public int count(){
		ensureEditable();
		return cnt;
	}
	
	Node ensureEditable(Node node){
		if(node.edit == root.edit)
			return node;
		return new Node(root.edit, node.array.clone());
	}

	void ensureEditable(){
		Thread owner = root.edit.get();
		if(owner == Thread.currentThread())
			return;
		if(owner != null)
			throw new IllegalAccessError("Transient used by non-owner thread");
		throw new IllegalAccessError("Transient used after persistent! call");

//		root = editableRoot(root);
//		tail = editableTail(tail);
	}

	static Node editableRoot(Node node){
		return new Node(new AtomicReference<Thread>(Thread.currentThread()), node.array.clone());
	}

	public PersistentVector persistent(){
		ensureEditable();
//		Thread owner = root.edit.get();
//		if(owner != null && owner != Thread.currentThread())
//			{
//			throw new IllegalAccessError("Mutation release by non-owner thread");
//			}
		root.edit.set(null);
		Object[] trimmedTail = new Object[cnt-tailoff()];
		System.arraycopy(tail,0,trimmedTail,0,trimmedTail.length);
		return new PersistentVector(cnt, shift, root, trimmedTail);
	}

	static Object[] editableTail(Object[] tl){
		Object[] ret = new Object[32];
		System.arraycopy(tl,0,ret,0,tl.length);
		return ret;
	}

	public TransientVector conj(Object val){
		ensureEditable();
		int i = cnt;
		//room in tail?
		if(i - tailoff() < 32)
			{
			tail[i & 0x01f] = val;
			++cnt;
			return this;
			}
		//full tail, push into tree
		Node newroot;
		Node tailnode = new Node(root.edit, tail);
		tail = new Object[32];
		tail[0] = val;
		int newshift = shift;
		//overflow root?
		if((cnt >>> 5) > (1 << shift))
			{
			newroot = new Node(root.edit);
			newroot.array[0] = root;
			newroot.array[1] = newPath(root.edit,shift, tailnode);
			newshift += 5;
			}
		else
			newroot = pushTail(shift, root, tailnode);
		root = newroot;
		shift = newshift;
		++cnt;
		return this;
	}

	private Node pushTail(int level, Node parent, Node tailnode){
		//if parent is leaf, insert node,
		// else does it map to an existing child? -> nodeToInsert = pushNode one more level
		// else alloc new path
		//return  nodeToInsert placed in parent
		parent = ensureEditable(parent);
		int subidx = ((cnt - 1) >>> level) & 0x01f;
		Node ret = parent;
		Node nodeToInsert;
		if(level == 5)
			{
			nodeToInsert = tailnode;
			}
		else
			{
			Node child = (Node) parent.array[subidx];
			nodeToInsert = (child != null) ?
			               pushTail(level - 5, child, tailnode)
			                               : newPath(root.edit, level - 5, tailnode);
			}
		ret.array[subidx] = nodeToInsert;
		return ret;
	}

	final private int tailoff(){
		if(cnt < 32)
			return 0;
		return ((cnt-1) >>> 5) << 5;
	}

	private Object[] arrayFor(int i){
		if(i >= 0 && i < cnt)
			{
			if(i >= tailoff())
				return tail;
			Node node = root;
			for(int level = shift; level > 0; level -= 5)
				node = (Node) node.array[(i >>> level) & 0x01f];
			return node.array;
			}
		throw new IndexOutOfBoundsException();
	}

	private Object[] editableArrayFor(int i){
		if(i >= 0 && i < cnt)
			{
			if(i >= tailoff())
				return tail;
			Node node = root;
			for(int level = shift; level > 0; level -= 5)
				node = ensureEditable((Node) node.array[(i >>> level) & 0x01f]);
			return node.array;
			}
		throw new IndexOutOfBoundsException();
	}

	public Object valAt(Object key){
		//note - relies on ensureEditable in 2-arg valAt
		return valAt(key, null);
	}

	public Object valAt(Object key, Object notFound){
		ensureEditable();
		if(Util.isInteger(key))
			{
			int i = ((Number) key).intValue();
			if(i >= 0 && i < cnt)
				return nth(i);
			}
		return notFound;
	}

	public Object invoke(Object arg1) {
		//note - relies on ensureEditable in nth
		if(Util.isInteger(arg1))
			return nth(((Number) arg1).intValue());
		throw new IllegalArgumentException("Key must be integer");
	}

	public Object nth(int i){
		ensureEditable();
		Object[] node = arrayFor(i);
		return node[i & 0x01f];
	}

	public Object nth(int i, Object notFound){
		if(i >= 0 && i < count())
			return nth(i);
		return notFound;
	}

	public TransientVector assocN(int i, Object val){
		ensureEditable();
		if(i >= 0 && i < cnt)
			{
			if(i >= tailoff())
				{
				tail[i & 0x01f] = val;
				return this;
				}

			root = doAssoc(shift, root, i, val);
			return this;
			}
		if(i == cnt)
			return conj(val);
		throw new IndexOutOfBoundsException();
	}

	public TransientVector assoc(Object key, Object val){
		//note - relies on ensureEditable in assocN
		if(Util.isInteger(key))
			{
			int i = ((Number) key).intValue();
			return assocN(i, val);
			}
		throw new IllegalArgumentException("Key must be integer");
	}

	private Node doAssoc(int level, Node node, int i, Object val){
		node = ensureEditable(node);
		Node ret = node;
		if(level == 0)
			{
			ret.array[i & 0x01f] = val;
			}
		else
			{
			int subidx = (i >>> level) & 0x01f;
			ret.array[subidx] = doAssoc(level - 5, (Node) node.array[subidx], i, val);
			}
		return ret;
	}

	public TransientVector pop(){
		ensureEditable();
		if(cnt == 0)
			throw new IllegalStateException("Can't pop empty vector");
		if(cnt == 1)
			{
			cnt = 0;
			return this;
			}
		int i = cnt - 1;
		//pop in tail?
		if((i & 0x01f) > 0)
			{
			--cnt;
			return this;
			}

		Object[] newtail = editableArrayFor(cnt - 2);

		Node newroot = popTail(shift, root);
		int newshift = shift;
		if(newroot == null)
			{
			newroot = new Node(root.edit);
			}
		if(shift > 5 && newroot.array[1] == null)
			{
			newroot = ensureEditable((Node) newroot.array[0]);
			newshift -= 5;
			}
		root = newroot;
		shift = newshift;
		--cnt;
		tail = newtail;
		return this;
	}

	private Node popTail(int level, Node node){
		node = ensureEditable(node);
		int subidx = ((cnt - 2) >>> level) & 0x01f;
		if(level > 5)
			{
			Node newchild = popTail(level - 5, (Node) node.array[subidx]);
			if(newchild == null && subidx == 0)
				return null;
			else
				{
				Node ret = node;
				ret.array[subidx] = newchild;
				return ret;
				}
			}
		else if(subidx == 0)
			return null;
		else
			{
			Node ret = node;
			ret.array[subidx] = null;
			return ret;
			}
	}
}
/*
static public void main(String[] args){
	if(args.length != 3)
		{
		System.err.println("Usage: PersistentVector size writes reads");
		return;
		}
	int size = Integer.parseInt(args[0]);
	int writes = Integer.parseInt(args[1]);
	int reads = Integer.parseInt(args[2]);
//	Vector v = new Vector(size);
	ArrayList v = new ArrayList(size);
//	v.setSize(size);
	//PersistentArray p = new PersistentArray(size);
	PersistentVector p = PersistentVector.EMPTY;
//	MutableVector mp = p.mutable();

	for(int i = 0; i < size; i++)
		{
		v.add(i);
//		v.set(i, i);
		//p = p.set(i, 0);
		p = p.cons(i);
//		mp = mp.conj(i);
		}

	Random rand;

	rand = new Random(42);
	long tv = 0;
	System.out.println("ArrayList");
	long startTime = System.nanoTime();
	for(int i = 0; i < writes; i++)
		{
		v.set(rand.nextInt(size), i);
		}
	for(int i = 0; i < reads; i++)
		{
		tv += (Integer) v.get(rand.nextInt(size));
		}
	long estimatedTime = System.nanoTime() - startTime;
	System.out.println("time: " + estimatedTime / 1000000);
	System.out.println("PersistentVector");
	rand = new Random(42);
	startTime = System.nanoTime();
	long tp = 0;

//	PersistentVector oldp = p;
	//Random rand2 = new Random(42);

	MutableVector mp = p.mutable();
	for(int i = 0; i < writes; i++)
		{
//		p = p.assocN(rand.nextInt(size), i);
		mp = mp.assocN(rand.nextInt(size), i);
//		mp = mp.assoc(rand.nextInt(size), i);
		//dummy set to force perverse branching
		//oldp =	oldp.assocN(rand2.nextInt(size), i);
		}
	for(int i = 0; i < reads; i++)
		{
//		tp += (Integer) p.nth(rand.nextInt(size));
		tp += (Integer) mp.nth(rand.nextInt(size));
		}
//	p = mp.immutable();
	//mp.cons(42);
	estimatedTime = System.nanoTime() - startTime;
	System.out.println("time: " + estimatedTime / 1000000);
	for(int i = 0; i < size / 2; i++)
		{
		mp = mp.pop();
//		p = p.pop();
		v.remove(v.size() - 1);
		}
	p = (PersistentVector) mp.immutable();
	//mp.pop();  //should fail
	for(int i = 0; i < size / 2; i++)
		{
		tp += (Integer) p.nth(i);
		tv += (Integer) v.get(i);
		}
	System.out.println("Done: " + tv + ", " + tp);

}
//  */
}
