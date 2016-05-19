
## Vector

* 从 IPersistentVector 接口看,vector 支持
  * 可以 assoc ,contains 判断
  * 是 Sequential
  * 是 stack
  * 可以 reverse。
  * 有索引的，随机访问.
  
* vector 也支持 update-in，还特别支持 `.indexOf` 和 `.lastIndexOf`

```
user=> (update-in [[1 2 3] [4 5 6]] [0 1] (constantly 100))
[[1 100 3] [4 5 6]]
``` 
 
 * subvec 是 O(1) 的开销。因为他是和父集合共享数据，只是有自己的 start 和 end
 * vector 本身是函数，接受一个整数作为参数，调用 `nth(n)`
 * 支持 get/find/assoc，只是 key 都是整数 index，`find` 很特别，返回一个 entry
 
 ```clojure
 user=> (find [1 2 3] 2)
[2 3]
 ```