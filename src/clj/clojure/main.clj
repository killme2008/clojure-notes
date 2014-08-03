;; Copyright (c) Rich Hickey All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse Public
;; License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can be found
;; in the file epl-v10.html at the root of this distribution. By using this
;; software in any fashion, you are agreeing to be bound by the terms of
;; this license. You must not remove this notice, or any other, from this
;; software.

;; Originally contributed by Stephen C. Gilardi

(ns ^{:doc "Top-level main function for Clojure REPL and scripts."
       :author "Stephen C. Gilardi and Rich Hickey"}
  clojure.main
  (:refer-clojure :exclude [with-bindings])
  (:import (clojure.lang Compiler Compiler$CompilerException
                         LineNumberingPushbackReader RT))
  ;;(:use [clojure.repl :only (demunge root-cause stack-element-str)])
  )

;;此处先声明，后续才定义这个入口函数
(declare main)

;;;;;;;;;;;;;;;;;;; redundantly copied from clojure.repl to avoid dep ;;;;;;;;;;;;;;
#_(defn root-cause [x] x)
#_(defn stack-element-str
  "Returns a (possibly unmunged) string representation of a StackTraceElement"
  {:added "1.3"}
  [^StackTraceElement el]
  (.getClassName el))

;;以下三个函数都是为了让错误信息更可读，clojure的错误信息广被诟病
;;主要提供给 clojure.repl/pst 函数使用

;;美化函数名称，例如将clojure.core$eval变成clojure.core/eval
;;将 user$test_QMARK_ 变成 user/test? 等等
;;符号的映射表放在 clojure.lang.Compiler/CHAR_MAP 中
(defn demunge
  "Given a string representation of a fn class,
  as in a stack trace element, returns a readable version."
  {:added "1.3"}
  [fn-name]
  (clojure.lang.Compiler/demunge fn-name))

(defn root-cause
  "Returns the initial cause of an exception or error by peeling off all of
  its wrappers"
  {:added "1.3"}
  [^Throwable t]
  (loop [cause t]
    (if (and (instance? clojure.lang.Compiler$CompilerException cause)
             (not= (.source ^clojure.lang.Compiler$CompilerException cause) "NO_SOURCE_FILE"))
      cause
      (if-let [cause (.getCause cause)]
        (recur cause)
        cause))))

(defn stack-element-str
  "Returns a (possibly unmunged) string representation of a StackTraceElement"
  {:added "1.3"}
  [^StackTraceElement el]
  (let [file (.getFileName el)
        clojure-fn? (and file (or (.endsWith file ".clj")
                                  (= file "NO_SOURCE_FILE")))]
    (str (if clojure-fn?
           ;;如果是clojure函数，将丑陋的函数类名转成 clojure 名称，前文提到。
           (demunge (.getClassName el))
           ;;否则，转成 x.y 的 java 名称
           (str (.getClassName el) "." (.getMethodName el)))
         ;;最后加上行号
         " (" (.getFileName el) ":" (.getLineNumber el) ")")))
;;;;;;;;;;;;;;;;;;; end of redundantly copied from clojure.repl to avoid dep ;;;;;;;;;;;;;;


(defmacro with-bindings
  "Executes body in the context of thread-local bindings for several vars
  that often need to be set!: *ns* *warn-on-reflection* *math-context*
  *print-meta* *print-length* *print-level* *compile-path*
  *command-line-args* *1 *2 *3 *e"
  [& body]
  `(binding [*ns* *ns*
             *warn-on-reflection* *warn-on-reflection*
             *math-context* *math-context*
             *print-meta* *print-meta*
             *print-length* *print-length*
             *print-level* *print-level*
             *data-readers* *data-readers*
             *default-data-reader-fn* *default-data-reader-fn*
             *compile-path* (System/getProperty "clojure.compile.path" "classes")
             *command-line-args* *command-line-args*
             *unchecked-math* *unchecked-math*
             *assert* *assert*
             *1 nil
             *2 nil
             *3 nil
             *e nil]
     ~@body))

;;打印 REPL 提示，我们运行 clojure.main 看到的 user=> 这样的提示
(defn repl-prompt
  "Default :prompt hook for repl"
  []
  (printf "%s=> " (ns-name *ns*)))

(defn skip-if-eol
  "If the next character on stream s is a newline, skips it, otherwise
  leaves the stream untouched. Returns :line-start, :stream-end, or :body
  to indicate the relative location of the next character on s. The stream
  must either be an instance of LineNumberingPushbackReader or duplicate
  its behavior of both supporting .unread and collapsing all of CR, LF, and
  CRLF to a single \\newline."
  [s]
  (let [c (.read s)]
    (cond
     (= c (int \newline)) :line-start
     (= c -1) :stream-end
     ;;读出来的 c 塞回去，保持 untouched，返回 :body
     :else (do (.unread s c) :body))))

(defn skip-whitespace
  "Skips whitespace characters on stream s. Returns :line-start, :stream-end,
  or :body to indicate the relative location of the next character on s.
  Interprets comma as whitespace and semicolon as comment to end of line.
  Does not interpret #! as comment to end of line because only one
  character of lookahead is available. The stream must either be an
  instance of LineNumberingPushbackReader or duplicate its behavior of both
  supporting .unread and collapsing all of CR, LF, and CRLF to a single
  \\newline."
  [s]
  (loop [c (.read s)]
    (cond
     (= c (int \newline)) :line-start
     (= c -1) :stream-end
     ;; 注释，读取下一行
     (= c (int \;)) (do (.readLine s) :line-start)
     ;; 注意，逗号在 clojure 里也是被忽略的
     (or (Character/isWhitespace (char c)) (= c (int \,))) (recur (.read s))
     :else (do (.unread s c) :body))))

;; 注释写的很详尽了。
(defn repl-read
  "Default :read hook for repl. Reads from *in* which must either be an
  instance of LineNumberingPushbackReader or duplicate its behavior of both
  supporting .unread and collapsing all of CR, LF, and CRLF into a single
  \\newline. repl-read:
    - skips whitespace, then
      - returns request-prompt on start of line, or
      - returns request-exit on end of stream, or
      - reads an object from the input stream, then
        - skips the next input character if it's end of line, then
        - returns the object."
  [request-prompt request-exit]
  (or ({:line-start request-prompt :stream-end request-exit}
       (skip-whitespace *in*))
      (let [input (read)]
        (skip-if-eol *in*)
        input)))

(defn repl-exception
  "Returns the root cause of throwables"
  [throwable]
  (root-cause throwable))

(defn repl-caught
  "Default :caught hook for repl"
  [e]
  (let [ex (repl-exception e)
        tr (.getStackTrace ex)
        el (when-not (zero? (count tr)) (aget tr 0))]
    ;;绑定标准输出到错误输出，然后打印异常堆栈
    (binding [*out* *err*]
      (println (str (-> ex class .getSimpleName)
                    " " (.getMessage ex) " "
                    (when-not (instance? clojure.lang.Compiler$CompilerException ex)
                      (str " " (if el (stack-element-str el) "[trace missing]"))))))))

;;REPL 默认加载的 lib 列表
(def ^{:doc "A sequence of lib specs that are applied to `require`
by default when a new command-line REPL is started."} repl-requires
  '[[clojure.repl :refer (source apropos dir pst doc find-doc)]
    [clojure.java.javadoc :refer (javadoc)]
    [clojure.pprint :refer (pp pprint)]])

;; *read-eval* 来控制 clojure reader 的行为，要不要自动 eval，默认true
;; 可以通过java -Dclojure.read.eval=false来控制它的行为
;; 如果是 unknown 则所有 read 将失败，除非明确设置 true 或者 false
;; 不过这个宏是强制将 unknown 设置为 true 了，防止失败。
(defmacro with-read-known
  "Evaluates body with *read-eval* set to a \"known\" value,
   i.e. substituting true for :unknown if necessary."
  [& body]
  `(binding [*read-eval* (if (= :unknown *read-eval*) true *read-eval*)]
     ~@body))

;;启动 REPL，传入一堆 hook 函数来定制行为
(defn repl
  "Generic, reusable, read-eval-print loop. By default, reads from *in*,
  writes to *out*, and prints exception summaries to *err*. If you use the
  default :read hook, *in* must either be an instance of
  LineNumberingPushbackReader or duplicate its behavior of both supporting
  .unread and collapsing CR, LF, and CRLF into a single \\newline. Options
  are sequential keyword-value pairs. Available options and their defaults:

     - :init, function of no arguments, initialization hook called with
       bindings for set!-able vars in place.
       default: #()

     - :need-prompt, function of no arguments, called before each
       read-eval-print except the first, the user will be prompted if it
       returns true.
       default: (if (instance? LineNumberingPushbackReader *in*)
                  #(.atLineStart *in*)
                  #(identity true))

     - :prompt, function of no arguments, prompts for more input.
       default: repl-prompt

     - :flush, function of no arguments, flushes output
       default: flush

     - :read, function of two arguments, reads from *in*:
         - returns its first argument to request a fresh prompt
           - depending on need-prompt, this may cause the repl to prompt
             before reading again
         - returns its second argument to request an exit from the repl
         - else returns the next object read from the input stream
       default: repl-read

     - :eval, function of one argument, returns the evaluation of its
       argument
       default: eval

     - :print, function of one argument, prints its argument to the output
       default: prn

     - :caught, function of one argument, a throwable, called when
       read, eval, or print throws an exception or error
       default: repl-caught"
  [& options]
  ;;包装当前线程的 context class loader 为  clojure.lang.DynamicClassLoader
  ;;Clojure 编译器动态生成的类都是通过 clojure.lang.DynamicClassLoader 定义和加载的
  ;; 这里是为了让当前线程可以加载到 clojure 的类库
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))
  (let [{:keys [init need-prompt prompt flush read eval print caught]
         :or {init        #()
              need-prompt (if (instance? LineNumberingPushbackReader *in*)
                            #(.atLineStart ^LineNumberingPushbackReader *in*)
                            #(identity true)) ;;用于判断是否需要打印提示的谓词函数
              prompt      repl-prompt  ;;打印提示函数，默认就是前面的 repl-prompt
              flush       flush  ;;缓冲区刷新函数
              read        repl-read  ;;读取函数
              eval        eval   ;;求值函数
              print       prn    ;;打印函数
              caught      repl-caught}}  ;;异常捕获函数，默认就是前面的 repl-caught，返回root cause
        ;;执行初始化选项函数
        (apply hash-map options)
        request-prompt (Object.)  ;;标示对象，表示要打印提示了
        request-exit (Object.)    ;;标示exit的对象
        read-eval-print
        (fn []
          (try
            (let [read-eval *read-eval*
                  input (with-read-known (read request-prompt request-exit))]
              ;;利用 or 的短路特性，要么打印提示或者退出，要么读取某个值，并eval
             (or (#{request-prompt request-exit} input)
                 (let [value (binding [*read-eval* read-eval] (eval input))]
                   ;;打印求值结果
                   (print value)
                   ;;设置 *3 *2 *1 对象，表示上次、上上次，上上上次求值结果
                   (set! *3 *2)
                   (set! *2 *1)
                   (set! *1 value))))
           (catch Throwable e
             ;;执行 caught 函数，默认是repl-caught，返回root cause
             (caught e)
             ;;如果有异常，设置 *e
             (set! *e e))))]
    (with-bindings
     (try
      (init)
      (catch Throwable e
        (caught e)
        (set! *e e)))
     (prompt)
     (flush)
      ;;read eval print 循环，通过loop实现
     (loop []
       (when-not
         ;;返回值不是 request-exit 就重复循环
       	 (try (identical? (read-eval-print) request-exit)
	            (catch Throwable e
        	   (caught e)
        	   (set! *e e)
        	   nil))
         (when (need-prompt)
           (prompt)
           (flush))
         ;;回到 loop 位置，再次循环
         (recur))))))

;; 执行传入的clojure脚本
;; 如果是 @ 开头的脚本，就认为是相对于 classpath
;; 否则就是普通的文件路径
(defn load-script
  "Loads Clojure source from a file or resource given its path. Paths
  beginning with @ or @/ are considered relative to classpath."
  [^String path]
  (if (.startsWith path "@")
    (RT/loadResourceScript
     (.substring path (if (.startsWith path "@/") 2 1)))
    (Compiler/loadFile path)))

;;加载 -i 选项的 clojure 脚本
(defn- init-opt
  "Load a script"
  [path]
  (load-script path))

;; 执行 -e 的表达式
(defn- eval-opt
  "Evals expressions in str, prints each non-nil result using prn"
  [str]
  (let [eof (Object.)  ;;毒丸对象，表示到达输入流的末尾
        reader (LineNumberingPushbackReader. (java.io.StringReader. str))]
      ;; 使用前面提到的 with-read-known，保证 read 不会因为 unknow 失败
      ;; 不停地 read -> eval 直到遇到 eof 对象
      (loop [input (with-read-known (read reader false eof))]
        (when-not (= input eof)
          (let [value (eval input)]
            (when-not (nil? value)
              (prn value))
            (recur (with-read-known (read reader false eof))))))))

;;表驱动法，根据启动选项返回一个函数执行
(defn- init-dispatch
  "Returns the handler associated with an init opt"
  [opt]
  ({"-i"     init-opt
    "--init" init-opt
    "-e"     eval-opt
    "--eval" eval-opt} opt))

;; 执行启动选项
(defn- initialize
  "Common initialize routine for repl, script, and null opts"
  [args inits]
  ;; 进入 user namespace
  (in-ns 'user)
  ;; 绑定 *command-line-args* 到命令行参数
  (set! *command-line-args* args)
  ;; 一一执行启动选项
  (doseq [[opt arg] inits]
    ((init-dispatch opt) arg)))

;; 有 -m 选项，调用某个 namespace 的 -main 函数
(defn- main-opt
  "Call the -main function from a namespace with string arguments from
  the command line."
  [[_ main-ns & args] inits]
  (with-bindings
    (initialize args inits)
    ;; require namespace 后，使用 apply 调用 -main 函数，并传入参数
    (apply (ns-resolve (doto (symbol main-ns) require) '-main) args)))

;; 有 -r 选项，启动 repl
(defn- repl-opt
  "Start a repl with args and inits. Print greeting if no eval options were
  present"
  [[_ & args] inits]
  ;; 没有 -e 执行代码，直接打印 clojure 版本号
  (when-not (some #(= eval-opt (init-dispatch (first %))) inits)
    (println "Clojure" (clojure-version)))
  ;; 启动REPL，传入 init 函数，这个函数就做两个事情
  ;; 1. 执行初始选项 -i 和 -e（如果有的话）
  ;; 2. 自动 require repl需要加载的类库
  (repl :init (fn []
                (initialize args inits)
                (apply require repl-requires)))
  (prn)
  ;;退出 repl 循环，退出 JVM
  (System/exit 0))

;;执行脚本，注意到 "-" 表示标准输入，遵循 unix 传统
(defn- script-opt
  "Run a script from a file, resource, or standard in with args and inits"
  [[path & args] inits]
  (with-bindings
    (initialize args inits)
    (if (= path "-")
      (load-reader *in*)
      (load-script path))))

;;没有 main-opt ，直接启动吧
(defn- null-opt
  "No repl or script opt present, just bind args and run inits"
  [args inits]
  (with-bindings
    (initialize args inits)))

;;打印 main 函数的文档，也就是帮助菜单
(defn- help-opt
  "Print help text for main"
  [_ _]
  (println (:doc (meta (var main)))))

;; 有命令行参数的启动行为，进入这里，根据命令行参数返回一个执行函数
;; 典型的表启动法，也是 cojure 的常见形式
(defn- main-dispatch
  "Returns the handler associated with a main option"
  [opt]
  (or
   ({"-r"     repl-opt
     "--repl" repl-opt
     "-m"     main-opt
     "--main" main-opt
     nil      null-opt
     "-h"     help-opt
     "--help" help-opt
     "-?"     help-opt} opt)
   script-opt))

;; 兼容 1.3 之前的 clojure.lang.Repl 方式启动 REPL
(defn- legacy-repl
  "Called by the clojure.lang.Repl.main stub to run a repl with args
  specified the old way"
  [args]
  (println "WARNING: clojure.lang.Repl is deprecated.
Instead, use clojure.main like this:
java -cp clojure.jar clojure.main -i init.clj -r args...")
  (let [[inits [sep & args]] (split-with (complement #{"--"}) args)]
    (repl-opt (concat ["-r"] args) (map vector (repeat "-i") inits))))

;;兼容 clojure 1.3.0 之前的 clojure.lang.Script 方式运行脚本
(defn- legacy-script
  "Called by the clojure.lang.Script.main stub to run a script with args
  specified the old way"
  [args]
  (println "WARNING: clojure.lang.Script is deprecated.
Instead, use clojure.main like this:
java -cp clojure.jar clojure.main -i init.clj script.clj args...")
  (let [[inits [sep & args]] (split-with (complement #{"--"}) args)]
    (null-opt args (map vector (repeat "-i") inits))))

;; 万物的开端，在 clojure.main.java 文件里调用，值得注意的几个选项：
;; (1) -i xxx.clj 加载一个 clojure 文件来设定一些默认行为
;; (2) -m namespace 指定调用某个 namespace 的 -main 函数。
;; (3) -r 启动 REPL
;; 传入某个文件作为参数，就直接运行
;; 注意到，强制要求 init-opt 必须在 main-opt 之前
(defn main
  "Usage: java -cp clojure.jar clojure.main [init-opt*] [main-opt] [arg*]

  With no options or args, runs an interactive Read-Eval-Print Loop

  init options:
    -i, --init path     Load a file or resource
    -e, --eval string   Evaluate expressions in string; print non-nil values

  main options:
    -m, --main ns-name  Call the -main function from a namespace with args
    -r, --repl          Run a repl
    path                Run a script from from a file or resource
    -                   Run a script from standard input
    -h, -?, --help      Print this help message and exit

  operation:
    - Establishes thread-local bindings for commonly set!-able vars
    - Enters the user namespace
    - Binds *command-line-args* to a seq of strings containing command line
      args that appear after any main option
    - Runs all init options in order
    - Calls a -main function or runs a repl or script if requested

  上面详细描述了启动做了什么事情：
    - 建立可以set!的 thread-local 绑定
    - 进入user namespace
    - 绑定 *command-line-args* 指向传入的命令行参数
    - 按顺序执行启动选项，如 -i 和 -e
    - 启动REPl，或者执行-main函数，或者执行传入的script文件

  The init options may be repeated and mixed freely, but must appear before
  any main option. The appearance of any eval option before running a repl
  suppresses the usual repl greeting message: \"Clojure ~(clojure-version)\".

  Paths may be absolute or relative in the filesystem or relative to
  classpath. Classpath-relative paths have prefix of @ or @/"
  [& args]
  (try
   ;;如果传入命令行参数，解析再决定如何执行
   (if args
     (loop [[opt arg & more :as args] args inits []]
       ;;如果是启动选项，如 -i 或者 -e，收集到 inits vector
       (if (init-dispatch opt)
         (recur more (conj inits [opt arg]))
         ;;开始启动,opt已经不是启动选项了
         ((main-dispatch opt) args inits)))
     ;;没有命令行参数，直接启动 repl
     (repl-opt nil nil))
   (finally
     ;;强制刷新输出缓冲区，打印
     (flush))))

