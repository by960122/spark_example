package project.app

import com.alibaba.fastjson.serializer.SerializeConfig
import com.alibaba.fastjson.{JSON, JSONArray, JSONObject}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.kafka010.{HasOffsetRanges, OffsetRange}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import project.model.{PageActionLog, PageDisplayLog, PageLog, StartLog}
import tools.{KafkaTools, OffsetsTools}


/**
 * @author: BYDylan
 * @date: 2023/3/1
 * @description: 日志数据的消费分流
 */
object OdsBaseLogApp {
  def main(args: Array[String]): Unit = {
    // 1.准备实时环境
    // 注意并行度与Kafka中topic的分区个数的对应关系
    val sparkConf: SparkConf = new SparkConf().setAppName("ods_base_log_app").setMaster("local[4]")
    val ssc: StreamingContext = new StreamingContext(sparkConf, Seconds(5))

    // 2.从kafka中消费数据
    val topicName: String = "ODS_BASE_LOG_0301" // 对应生成器配置中的主题名
    val groupId: String = "ODS_BASE_LOG_GROUP_0301"

    // 从Redis中读取offset, 指定offset进行消费
    val offsets: Map[TopicPartition, Long] = OffsetsTools.readOffset(topicName, groupId)

    var kafkaDStream: InputDStream[ConsumerRecord[String, String]] = null
    if (offsets != null && offsets.nonEmpty) {
      // 指定offset进行消费
      kafkaDStream = KafkaTools.getKafkaDStream(ssc, topicName, groupId, offsets)
    } else {
      // 默认offset进行消费
      kafkaDStream = KafkaTools.getKafkaDStream(ssc, topicName, groupId)
    }

    // 补充: 从当前消费到的数据中提取offsets, 不对流中的数据做任何处理.
    var offsetRanges: Array[OffsetRange] = null
    val offsetRangesDStream: DStream[ConsumerRecord[String, String]] = kafkaDStream.transform(
      rdd => {
        offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges //  在哪里执行? driver
        rdd
      }
    )
    // 3. 处理数据
    // 3.1 转换数据结构
    val jsonObjDStream: DStream[JSONObject] = offsetRangesDStream.map(
      consumerRecord => {
        // 获取ConsumerRecord中的value,value就是日志数据
        val log: String = consumerRecord.value()
        // 转换成Json对象
        val jsonObj: JSONObject = JSON.parseObject(log)
        // 返回
        jsonObj
      }
    )
    // 3.2 分流
    val DWD_PAGE_LOG_TOPIC: String = "DWD_PAGE_LOG_TOPIC_0301" //  页面访问
    val DWD_PAGE_DISPLAY_TOPIC: String = "DWD_PAGE_DISPLAY_TOPIC_0301" // 页面曝光
    val DWD_PAGE_ACTION_TOPIC: String = "DWD_PAGE_ACTION_TOPIC_0301" // 页面事件
    val DWD_START_LOG_TOPIC: String = "DWD_START_LOG_TOPIC_0301" //  启动数据
    val DWD_ERROR_LOG_TOPIC: String = "DWD_ERROR_LOG_TOPIC_0301" //  错误数据
    // 分流规则:
    // 错误数据: 不做任何的拆分, 只要包含错误字段,直接整条数据发送到对应的topic
    // 页面数据: 拆分成页面访问, 曝光, 事件 分别发送到对应的topic
    // 启动数据: 发动到对应的topic
    jsonObjDStream.foreachRDD(
      rdd => {
        // 刷写 不能用 foreach
        rdd.foreachPartition(
          jsonObjIter => {
            for (jsonObj <- jsonObjIter) {
              // 分流过程
              // 分流错误数据
              val errObj: JSONObject = jsonObj.getJSONObject("err")
              if (errObj != null) {
                // 将错误数据发送到 DWD_ERROR_LOG_TOPIC
                KafkaTools.send(DWD_ERROR_LOG_TOPIC, jsonObj.toJSONString)
              } else {
                //  提取公共字段
                val commonObj: JSONObject = jsonObj.getJSONObject("common")
                val ar: String = commonObj.getString("ar")
                val uid: String = commonObj.getString("uid")
                val os: String = commonObj.getString("os")
                val ch: String = commonObj.getString("ch")
                val isNew: String = commonObj.getString("is_new")
                val md: String = commonObj.getString("md")
                val mid: String = commonObj.getString("mid")
                val vc: String = commonObj.getString("vc")
                val ba: String = commonObj.getString("ba")
                // 提取时间戳
                val ts: Long = jsonObj.getLong("ts")
                //  页面数据
                val pageObj: JSONObject = jsonObj.getJSONObject("page")
                if (pageObj != null) {
                  // 提取page字段
                  val pageId: String = pageObj.getString("page_id")
                  val pageItem: String = pageObj.getString("item")
                  val pageItemType: String = pageObj.getString("item_type")
                  val duringTime: Long = pageObj.getLong("during_time")
                  val lastPageId: String = pageObj.getString("last_page_id")
                  val sourceType: String = pageObj.getString("source_type")

                  // 封装成PageLog
                  var pageLog = PageLog(mid, uid, ar, ch, isNew, md, os, vc, ba, pageId, lastPageId, pageItem, pageItemType, duringTime, sourceType, ts)
                  // 发送到DWD_PAGE_LOG_TOPIC
                  KafkaTools.send(DWD_PAGE_LOG_TOPIC, JSON.toJSONString(pageLog, new SerializeConfig(true)))

                  // 提取曝光数据
                  val displaysJsonArr: JSONArray = jsonObj.getJSONArray("displays")
                  if (displaysJsonArr != null && displaysJsonArr.size() > 0) {
                    for (i <- 0 until displaysJsonArr.size()) {
                      // 循环拿到每个曝光
                      val displayObj: JSONObject = displaysJsonArr.getJSONObject(i)
                      // 提取曝光字段
                      val displayType: String = displayObj.getString("display_type")
                      val displayItem: String = displayObj.getString("item")
                      val displayItemType: String = displayObj.getString("item_type")
                      val posId: String = displayObj.getString("pos_id")
                      val order: String = displayObj.getString("order")

                      // 封装成PageDisplayLog
                      val pageDisplayLog = PageDisplayLog(mid, uid, ar, ch, isNew, md, os, vc, ba, pageId, lastPageId, pageItem, pageItemType, duringTime, sourceType, displayType, displayItem, displayItemType, order, posId, ts)
                      //  写到 DWD_PAGE_DISPLAY_TOPIC
                      KafkaTools.send(DWD_PAGE_DISPLAY_TOPIC, JSON.toJSONString(pageDisplayLog, new SerializeConfig(true)))
                    }
                  }
                  // 提取事件数据（课下完成）
                  val actionJsonArr: JSONArray = jsonObj.getJSONArray("actions")
                  if (actionJsonArr != null && actionJsonArr.size() > 0) {
                    for (i <- 0 until actionJsonArr.size()) {
                      val actionObj: JSONObject = actionJsonArr.getJSONObject(i)
                      // 提取字段
                      val actionId: String = actionObj.getString("action_id")
                      val actionItem: String = actionObj.getString("item")
                      val actionItemType: String = actionObj.getString("item_type")
                      val actionTs: Long = actionObj.getLong("ts")

                      // 封装PageActionLog
                      var pageActionLog =
                        PageActionLog(mid, uid, ar, ch, isNew, md, os, vc, ba, pageId, lastPageId, pageItem, pageItemType, duringTime, sourceType, actionId, actionItem, actionItemType, actionTs, ts)
                      // 写出到DWD_PAGE_ACTION_TOPIC
                      KafkaTools.send(DWD_PAGE_ACTION_TOPIC, JSON.toJSONString(pageActionLog, new SerializeConfig(true)))
                    }
                  }
                }
                //  启动数据（课下完成）
                val startJsonObj: JSONObject = jsonObj.getJSONObject("start")
                if (startJsonObj != null) {
                  // 提取字段
                  val entry: String = startJsonObj.getString("entry")
                  val loadingTime: Long = startJsonObj.getLong("loading_time")
                  val openAdId: String = startJsonObj.getString("open_ad_id")
                  val openAdMs: Long = startJsonObj.getLong("open_ad_ms")
                  val openAdSkipMs: Long = startJsonObj.getLong("open_ad_skip_ms")

                  // 封装StartLog
                  var startLog = StartLog(mid, uid, ar, ch, isNew, md, os, vc, ba, entry, openAdId, loadingTime, openAdMs, openAdSkipMs, ts)
                  // 写出DWD_START_LOG_TOPIC
                  KafkaTools.send(DWD_START_LOG_TOPIC, JSON.toJSONString(startLog, new SerializeConfig(true)))
                }
              }
            }
            // foreachPartition里面:  Executor端执行, 每批次每分区执行一次
            KafkaTools.flush()
          }
        )
        /*
        rdd.foreach(
          jsonObj => {
            // foreach里面: executor执行, 每条数据执行一次. 相当于是同步发送消息.
          }
        )
         */
        // foreachRDD里面,forech外面: Driver端执行,一批次执行一次(周期性) 分流是在executor端完成,如果在此处做刷写,刷的不是同一个对象的缓冲区.
        OffsetsTools.saveOffset(topicName, groupId, offsetRanges)
      }
    )
    // foreachRDD外面: Driver执行,每次启动程序执行一次.分流是在executor端完成,如果在此处做刷写,刷的不是同一个对象的缓冲区.
    ssc.start()
    ssc.awaitTermination()
  }
}