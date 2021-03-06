package com.hrong.flink.cep

import java.util

import lombok.Data
import org.apache.flink.cep.PatternSelectFunction
import org.apache.flink.cep.scala.pattern.Pattern
import org.apache.flink.cep.scala.{CEP, PatternStream}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks
import org.apache.flink.streaming.api.scala.{StreamExecutionEnvironment, _}
import org.apache.flink.streaming.api.watermark.Watermark

/**
  * flink-cep示例代码
  * cep简单使用
  */
object CepFun01 {
  def main(args: Array[String]): Unit = {
    val senv = StreamExecutionEnvironment.getExecutionEnvironment
    // 设置流数据时间类型为event-time
    senv.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val input = senv.fromElements(
      (Event(1, "first", 1.0), 2L),
      (Event(2, "second", 2.0), 1L),
      (Event(3, "third", 3.0), 3L),
      // 触发window操作
      (Event(4, "forth", 4.0), 5L),
      // 延迟数据
      (Event(5, "fifth", 5.0), 2L),
      // 触发window
      (Event(6, "fifth", 6.0), 9L)
    ).assignTimestampsAndWatermarks(new AssignerWithPunctuatedWatermarks[(Event, Long)] {
      // 事件时间
      var currentMaxTimestamp = 0L
      val maxOutOfOrderness = 2L
      var lastEmittedWatermark: Long = Long.MinValue


      override def extractTimestamp(element: (Event, Long), previousElementTimestamp: Long): Long = {
        val timestamp = element._2
        if (timestamp > currentMaxTimestamp) {
          currentMaxTimestamp = timestamp
        }
        println("water-mark：", checkAndGetNextWatermark(element, 0L))
        timestamp
      }

      override def checkAndGetNextWatermark(lastElement: (Event, Long), extractedTimestamp: Long): Watermark = {
        val potentialWM = currentMaxTimestamp - maxOutOfOrderness
        // 保证水印能依次递增
        if (potentialWM >= lastEmittedWatermark) {
          lastEmittedWatermark = potentialWM
        }
        new Watermark(lastEmittedWatermark)
      }
    }).map(_._1)

    /**
      * 1、首先要定义Pattern，start的条件为id=3，next的条件为score>=3，结束条件为score>=5
      * 意思是只要符合以id为3开头，并且接下来的第一条数据的score大于等于3，第二条数据大于等于5即满足pattern
      */
    val pattern = Pattern.begin[Event]("start").where(event => event.id == 3)
      .next("middle").where(event => event.score >= 3)
      .followedBy("end").where(event => event.score >= 5)

    /**
      * 2、通过CEP.pattern()方法将DataStream转化为PatternStream
      */
    val cepResult: PatternStream[Event] = CEP.pattern(input, pattern)
    input.print()

    /**
      * 3、将符合pattern的数据调用select方法对数据进行处理
      */
    cepResult.select(new PatternSelectFunction[Event, String] {
      override def select(pattern: util.Map[String, util.List[Event]]): String = {
        var res: String = ""
        if (pattern != null) {
          res = "start:【" + pattern.get("start").get(0) + "】 ->" +
            "middle: 【" + pattern.get("middle").get(0) + "】 ->" +
            "end: 【" + pattern.get("end").get(0) + "】"
        }
        res
      }
    }).print()
    // 输出： start:【Event(3,third,3.0)】 ->middle: 【Event(4,forth,4.0)】 ->end: 【Event(6,fifth,6.0)】
    // 解释：按照event-time进行cep匹配，id-5的数据为迟到的数据，所以事后3-4-6
    senv.execute(this.getClass.getName)
  }
}

@Data
case class Event(id: Int, name: String, score: Double)

