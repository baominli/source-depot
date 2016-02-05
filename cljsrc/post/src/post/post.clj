(ns post.core
  (:require [clojure.java.io :as io])
  (:require [clj-http.lite.client :as client])
  (:require [clojure.java.jdbc :as jdbc])
  (:require [clojure.data.json :as json])
  (:require [clojure.java.shell :as shell]))

;; need to prepare sqlite db before hand
(def db-spec {:classname "org.sqlite.JDBC"
              :subprotocol "sqlite" 
              :subname "sub.db"})

(defn create-table! [] 
  (do
    (jdbc/execute! db-spec ["CREATE TABLE IF NOT EXISTS processed (id Integer Primary Key AutoIncrement, name varchar NOT NULL,
                                                        status Integer, createtime datetime default current_timestamp)"])
    (jdbc/execute! db-spec ["CREATE TABLE IF NOT EXISTS submitted (id Integer Primary Key AutoIncrement, name varchar NOT NULL,
                            phone varchar NOT NULL, birthday varchar NOT NULL, sex Integer, 
                            give Integer, dc1 varchar, dc2 varchar, status Integer, response varchar,
                            wifi_mac varchar, user_mac varchar, uploadtime datetime, 
                            createtime datetime default current_timestamp)"])))

(defn file-exists? [name]
  (let [res (jdbc/query db-spec ["SELECT * FROM processed WHERE name = ?" name])]
    (> (count res) 0)))

(defn insert-file! [name status]
   (jdbc/execute! db-spec ["INSERT INTO processed (name, status) values (?, ?)"
                 name, status]))


(defn submit-exists? [phone name]
 (let [res (jdbc/query db-spec ["SELECT * FROM submitted WHERE phone = ? and name = ?" phone name])]
    (> (count res) 0)))

(defn insert! 
  ([phone name sex birthday]
  (jdbc/execute! db-spec ["INSERT INTO submitted (name, sex, birthday, phone) values (?, ?, ?, ?)"
                 name, sex, birthday, phone]))
  ([phone name give dc1 dc2 sex birthday status response user_mac wifi_mac uploadtime]
  (jdbc/execute! db-spec ["INSERT INTO submitted (give, dc1, dc2, name, sex, birthday, phone, 
                          status, response, user_mac, wifi_mac, uploadtime) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                 give, dc1, dc2, name, sex, birthday, phone, status, response user_mac wifi_mac uploadtime])))

(defn get-value [text element]
  (let [p1 (.indexOf text (str "name=\"" element) 0)]
    (if (> p1 0)
      (let [p2 (+ 1 (.length "value=") (.indexOf text "value=" p1))
            p3 (.indexOf text "\"" p2)]
        ;(prn (str element "=" (.subSequence text p2 p3)))
        (.subSequence text p2 p3)) "12345")))

(defn parse-text [txt]
  (do
   (prn txt)
  ;(prn (nth (.split txt "\\|") 6))
   (if-let [[_, _, _, uploadtime, user_mac, wifi_mac, js] (.split txt "\\|")]
     (try (let [obj (json/read-str js)]
            (assoc obj "uploadtime" uploadtime
                       "user_mac" user_mac
                        "wifi_mac" wifi_mac))
       (catch Exception e nil)))))

(defn submit 
  [phone name give dc1 dc2 sex birthday]
  (if-not (submit-exists? phone name)
     (let [r (client/get (str "http://sx.360doo.com/colligate/ywx/mobile/home_dc_d/index.aspx"
                           "?c=3985&p=18659&k=50CedN03P5w45LD1X92Hs9mj8m32i04Iod5V8Jt4fraRZaP83Vv6h4ad-9bR&0"))
           g (:body r)]
        (let [p (client/post (str "http://sx.360doo.com/colligate/ywx/mobile/home_dc_d/index.aspx" 
                            "?c=3985&p=18659&k=50CedN03P5w45LD1X92Hs9mj8m32i04Iod5V8Jt4fraRZaP83Vv6h4ad-9bR")
           {:form-params {:__EVENTTARGET "btn_submit"
                         :__EVENTARGUMENT ""
                         :__VIEWSTATE (get-value g "__VIEWSTATE")
                         :__VIEWSTATEGENERATOR (get-value g "__VIEWSTATEGENERATOR")
                         :__EVENTVALIDATION (get-value g "__EVENTVALIDATION")
                         :rdo_GivingWay give
                         :rdo_dc1 dc1
                         :rdo_dc2 dc2
                         :txt_name name
                         :rdo_sex sex
                         :txt_birthday birthday
                         :hid_birthday (apply str (.split birthday "-|/"))
                         :txt_phone phone}
         :content-type "application/x-www-form-urlencoded"})
          body (:body p)
          success (if (> (.indexOf body "提交成功" 0) 0) 1 0)]
        (insert! phone name give dc1 dc2 sex birthday success (if (= 1 success) "" body))))))


(defn clean-path! [path]
  (shell/sh
    "sh" "-c" (str " rm -rf " path))
  )

(defn unpack-zip! [inpath outpath]
  (shell/sh 
      "sh" "-c" 
      (str " unzip " inpath " -d " outpath))
  )

(defn unpack-zip-process-clean! [inpath outpath func]
  (do
    (prn inpath " ++ " outpath)
    (shell/sh 
      "sh" "-c" 
      (str " unzip " inpath " -d " outpath))
    (func outpath)
    ;(clean-path! outpath)
  ))

(defn extract [path]
  (doall (for [f (file-seq (java.io.File. path))
               :when (and (.isFile f) (.endsWith (.getPath f) ".zip"))]
           (unpack-zip! (.getPath f) (.getParent f)))))

(defn clean-logfiles [path]
  (doall (for [f (file-seq (java.io.File. path))
               :when (and (.isFile f) (.endsWith (.getPath f) ".log"))]
           (.delete f))))

(defn process-log [path]
  "process logs for the path"
  (doall (for [f (file-seq (java.io.File. path)) 
               :when (and (.isFile f) (.endsWith (.getPath f) ".log") (not (file-exists? (.getPath f))))]
           (do
            (insert-file! (.getPath f) 1)
            (doall (for [txt (line-seq (io/reader f))]
              (if-let [v (parse-text txt)]
               ;(prn v)
               (submit (v "txt_phone") 
                       (v "txt_name") 
                       (v "rdo_GivingWay") 
                       (v "rdo_dc1") 
                       (v "rdo_dc2") 
                       (v "rdo_sex")
                       (v "txt_birthday")
                       ))))))))

(defn report-log [path]
  "report logs for the path"
  (doall (for [f (file-seq (java.io.File. path)) 
               :when (and (.isFile f) (.endsWith (.getPath f) ".log") (not (file-exists? (.getPath f))))]
           (do
            (insert-file! (.getPath f) 1)
            (doall (for [txt (line-seq (io/reader f))]
              (if-let [v (parse-text txt)]
                (do
                  (prn v)
                  (if (not (submit-exists? (v "txt_phone") (v "txt_name")))
                    (insert! (v "txt_phone") 
                       (v "txt_name") 
                       (v "rdo_GivingWay") 
                       (v "rdo_dc1") 
                       (v "rdo_dc2") 
                       (v "rdo_sex")
                       (v "txt_birthday")
                       3 ""
                       (v "user_mac")
                       (v "wifi_mac")
                       (v "uploadtime")))))))))))

(defn process 
  ([path]
   (process path report-log))
  ([path func]
  "process the path for zip files, unzip each and delete the directory after each time."
      (doall (for [f (file-seq (java.io.File. path))
               :when (and (.isFile f) (.endsWith (.getPath f) ".zip") (not (file-exists? (.getPath f))))]
           (let [inpath (.getPath f) outpath (str (.getParent f) "/log")]
             (do
             (prn (str inpath " + " outpath))
             (unpack-zip-process-clean! inpath outpath func)
             )))))
  )
;(create-table!)
;(submit "13625072268" "来米" 1 "父母" "完全有必要" 1 "1977-03-14")
;(process "/data/trainwifi/upload/equip/50")
;(process "~/Downloads/201602" report-log)
