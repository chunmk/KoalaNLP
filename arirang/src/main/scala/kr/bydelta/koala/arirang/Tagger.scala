package kr.bydelta.koala.arirang

import java.util

import kr.bydelta.koala.Implicit._
import kr.bydelta.koala.POS
import kr.bydelta.koala.data.{Morpheme, Sentence, Word}
import kr.bydelta.koala.traits.CanTag
import org.apache.lucene.analysis.ko.morph.{AnalysisOutput, PatternConstants, WordSegmentAnalyzer}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/**
  * Created by bydelta on 17. 8. 19.
  */
class Tagger extends CanTag[Sentence] {
  val tagger = new WordSegmentAnalyzer

  override def tagParagraphRaw(text: String): Seq[Sentence] =
    if (text.trim.isEmpty) Seq.empty
    else {
      val list = new util.LinkedList[util.List[AnalysisOutput]]()
      tagger.analyze(text.trim, list, false)
      splitSentences(convertParagraph(text.trim, list.asScala.map(_.asScala.maxBy(_.getScore))))
    }

  override private[koala] def convert(result: Sentence): Sentence = result

  private[koala] def convertParagraph(text: String, result: Seq[AnalysisOutput]): Sentence = {
    var sentence = text
    var wordlist =
      result.filter(_.getSource.trim.nonEmpty).flatMap {
        word =>
          val words = ArrayBuffer[Word]()
          var surface = word.getSource.trim
          var morphs = Seq[Morpheme]()

          val (sentCurr, sentRemain) = sentence.splitAt(sentence.indexOf(surface))
          sentence = sentRemain.substring(surface.length)

          if (sentCurr.trim.nonEmpty) {
            morphs +:= Morpheme(sentCurr.trim, " ", POS.UE)
            surface = sentCurr + surface
          }
          morphs = morphs ++: interpretOutput(word)
          morphs = morphs.flatMap {
            // Find Special characters and separate them as morphemes
            case m@Morpheme(s, tag) if Tagger.SPRegex.findFirstMatchIn(s).isDefined ||
              Tagger.SFRegex.findFirstMatchIn(s).isDefined ||
              Tagger.filterRegex.findFirstMatchIn(s).isDefined =>
              s.split(Tagger.punctuationsSplit).map {
                case x if Tagger.SPRegex.findFirstMatchIn(x).isDefined =>
                  Morpheme(x, m.rawTag, POS.SP)
                case x if Tagger.SFRegex.findFirstMatchIn(x).isDefined =>
                  Morpheme(x, m.rawTag, POS.SF)
                case x if Tagger.SSRegex.findFirstMatchIn(x).isDefined =>
                  Morpheme(x, m.rawTag, POS.SS)
                case x if x.matches("\\s+") =>
                  Morpheme(x, m.rawTag, POS.TEMP)
                case x => Morpheme(x.trim, m.rawTag, tag)
              }
            case m => Seq(m)
          }

          // Now separate special characters as words
          while (morphs.exists(Tagger.checkset)) {
            val (morph, after) = morphs.splitAt(morphs.indexWhere(Tagger.checkset))
            val symbol = after.head.surface
            val (prev, next) = surface.splitAt(surface.indexOf(symbol))
            if (prev.trim.nonEmpty) {
              words.append(Word(surface = prev.trim, morphemes = morph))
            }
            if (symbol.trim.nonEmpty) {
              words.append(Word(surface = symbol.trim, morphemes = Seq(after.head)))
            }

            surface = next.substring(symbol.length)
            morphs = after.tail
          }

          if (surface.trim.nonEmpty) {
            words.append(Word(surface = surface.trim, morphemes = morphs))
          }

          words
      }

    if (sentence.trim.nonEmpty) {
      wordlist :+= Word(surface = sentence.trim,
        morphemes = Seq(Morpheme(surface = sentence.trim, rawTag = " ", tag = POS.UE)))
    }

    Sentence(wordlist)
  }

  private def interpretOutput(o: AnalysisOutput): Seq[Morpheme] = {
    val morphs = ArrayBuffer[Morpheme]()
    morphs.append(Morpheme(o.getStem.trim, "" + o.getPos, o.getPos match {
      case PatternConstants.POS_NPXM => POS.NNG
      case PatternConstants.POS_VJXV => POS.VV
      case PatternConstants.POS_AID => POS.MAG
      case _ => POS.SY
    }))

    if (o.getNsfx != null) {
      //NounSuffix
      morphs.append(Morpheme(o.getNsfx.trim, "_s", POS.XSN))
    }

    if (o.getPatn != 2 && o.getPatn != 22) {
      // 2: 체언 + 조사, 부사 + 조사 : '빨리도'
      if (o.getPatn == 3) {
        //* 체언 + 용언화접미사 + 어미 */
        morphs.append(Morpheme(o.getVsfx.trim, "_t", POS.XSV))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getEomi.trim, "_e", POS.EF))
      } else if (o.getPatn == 4) {
        //* 체언 + 용언화접미사 + '음/기' + 조사 */
        morphs.append(Morpheme(o.getVsfx.trim, "_t", POS.XSV))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getElist.get(0).trim, "_n", POS.ETN))
        morphs.append(Morpheme(o.getJosa.trim, "_j", POS.JX))
      } else if (o.getPatn == 5) {
        //* 체언 + 용언화접미사 + '아/어' + 보조용언 + 어미 */
        morphs.append(Morpheme(o.getVsfx.trim, "_t", POS.XSV))
        morphs.append(Morpheme(o.getElist.get(0).trim, "_c", POS.EC))
        morphs.append(Morpheme(o.getXverb.trim, "_W", POS.VX))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getEomi.trim, "_e", POS.EF))
      } else if (o.getPatn == 6) {
        //* 체언 + '에서/부터/에서부터' + '이' + 어미 */
        morphs.append(Morpheme(o.getJosa.trim, "_j", POS.JKB))
        morphs.append(Morpheme(o.getElist.get(0).trim, "_t", POS.VCP))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getEomi.trim, "_e", POS.EF))
      } else if (o.getPatn == 7) {
        //* 체언 + 용언화접미사 + '아/어' + 보조용언 + '음/기' + 조사 */
        morphs.append(Morpheme(o.getVsfx.trim, "_t", POS.XSV))
        morphs.append(Morpheme(o.getElist.get(0).trim, "_c", POS.EC))
        morphs.append(Morpheme(o.getXverb.trim, "_W", POS.VX))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getElist.get(0).trim, "_n", POS.ETN))
        morphs.append(Morpheme(o.getJosa.trim, "_j", POS.JX))
      } else if (o.getPatn == 11) {
        //* 용언 + 어미 */
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getEomi.trim, "_e", POS.EF))
      } else if (o.getPatn == 12) {
        //* 용언 + '음/기' + 조사 */
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getElist.get(0).trim, "_n", POS.ETN))
        morphs.append(Morpheme(o.getJosa.trim, "_j", POS.JX))
      } else if (o.getPatn == 13) {
        //* 용언 + '음/기' + '이' + 어미 */
        morphs.append(Morpheme(o.getElist.get(0).trim, "_n", POS.ETN))
        morphs.append(Morpheme(o.getElist.get(1).trim, "_s", POS.VCP))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getEomi.trim, "_e", POS.EF))
      } else if (o.getPatn == 14) {
        //* 용언 + '아/어' + 보조용언 + 어미 */
        morphs.append(Morpheme(o.getElist.get(0).trim, "_c", POS.EC))
        morphs.append(Morpheme(o.getXverb.trim, "_W", POS.VX))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getEomi.trim, "_e", POS.EF))
      } else if (o.getPatn == 15) {
        //* 용언 + '아/어' + 보조용언 + '음/기' + 조사 */
        morphs.append(Morpheme(o.getElist.get(1).trim, "_c", POS.EC))
        morphs.append(Morpheme(o.getXverb.trim, "_W", POS.VX))
        if (o.getPomi != null) {
          morphs.append(Morpheme(o.getPomi.trim, "_f", POS.EP))
        }

        morphs.append(Morpheme(o.getElist.get(0).trim, "_n", POS.ETN))
        morphs.append(Morpheme(o.getJosa.trim, "_j", POS.JX))
      }
    } else {
      morphs.append(Morpheme(o.getJosa.trim, "_j", POS.JX))
    }

    morphs
  }


  /**
    * 분석결과를 토대로 문장을 분리함.
    *
    * @param para 분리할 문단.
    * @param pos  현재 읽고있는 위치.
    * @param open 현재까지 열려있는 묶음기호 Stack.
    * @param acc  현재까지 분리된 문장들.
    * @return 문장단위로 분리된 결과
    */
  private def splitSentences(para: Seq[Word],
                             pos: Int = 0,
                             open: List[Char] = List(),
                             acc: ArrayBuffer[Sentence] = ArrayBuffer()): Seq[Sentence] =
  if (para.isEmpty) acc
  else {
    val rawEndmark = para.indexWhere(_.exists(POS.SF), pos)
    val rawParen = para.indexWhere({
      e =>
        (e.exists(POS.SS) ||
          Tagger.openParenRegex.findFirstMatchIn(e.surface).isDefined ||
          Tagger.closeParenRegex.findFirstMatchIn(e.surface).isDefined ||
          Tagger.quoteRegex.findFirstMatchIn(e.surface).isDefined) &&
          Tagger.matchRegex.findFirstIn(e.surface).isEmpty
    }, pos)

    val endmark = if (rawEndmark == -1) para.length else rawEndmark
    val paren = if (rawParen == -1) para.length else rawParen

    if (endmark == paren && paren == para.length) {
      acc += Sentence(para)
      acc
    } else if (open.isEmpty) {
      if (endmark < paren) {
        val (sent, next) = para.splitAt(endmark + 1)
        acc += Sentence(sent)
        splitSentences(next, 0, open, acc)
      } else {
        val parenStr = para(paren)
        val surface = Tagger.filterRegex.replaceAllIn(parenStr.surface, "")
        var nOpen = open
        if (Tagger.closeParenRegex.findFirstMatchIn(surface).isEmpty) {
          nOpen ++:= surface.toSeq
        }
        splitSentences(para, paren + 1, nOpen, acc)
      }
    } else {
      if (paren == para.length) {
        acc += Sentence(para)
        acc
      } else {
        val parenStr = para(paren)
        val surface = Tagger.filterRegex.replaceAllIn(parenStr.surface, "")
        var nOpen = open
        if (Tagger.openParenRegex.findFirstMatchIn(surface).isDefined) {
          nOpen ++:= surface.toSeq
        } else if (Tagger.closeParenRegex.findFirstMatchIn(surface).isDefined) {
          nOpen = nOpen.tail
        } else {
          val top = nOpen.head
          if (surface.last == top) nOpen = nOpen.tail
          else nOpen ++:= surface.toSeq
        }
        splitSentences(para, paren + 1, nOpen, acc)
      }
    }
  }
}

object Tagger {
  private val checkset = Seq(POS.SF, POS.SP, POS.SS, POS.TEMP)
  private val SFRegex = "(?U)[\\.\\?\\!]+".r
  private val SPRegex = "(?U)[,:;·/]+".r
  private val punctuationsSplit = "(?U)((?<=[,\\.:;\\?\\!/·\\s\'\"\\(\\[\\{<〔〈《「『【‘“\\)\\]\\}>〕〉》」』】’”]+)|" +
    "(?=[,\\.:;\\?\\!/·\\s\'\"\\(\\[\\{<〔〈《「『【‘“\\)\\]\\}>〕〉》」』】’”]+))"
  private val quoteRegex = "(?U)[\'\"]{1}".r
  private val openParenRegex = "(?U)[\\(\\[\\{<〔〈《「『【‘“]{1}".r
  private val closeParenRegex = "(?U)[\\)\\]\\}>〕〉》」』】’”]{1}".r
  private val matchRegex = ("(?U)(\'[^\']*\'|\"[^\"]*\"|\\([^\\(\\)]*\\)|\\[[^\\[\\]]*\\]|\\{[^\\{\\}]*\\}|" +
    "<[^<>]*>|〔[^〔〕]*〕|〈[^〈〉]*〉|《[^《》]*》|「[^「」]*」|『[^『』]*』|【[^【】]*】|‘[^‘’]*’|“[^“”]*”)").r
  private val filterRegex = "(?U)[^\'\"\\(\\[\\{<〔〈《「『【‘“\\)\\]\\}>〕〉》」』】’”]+".r
  private val SSRegex = "(?U)[\'\"\\(\\[\\{<〔〈《「『【‘“\\)\\]\\}>〕〉》」』】’”]+".r
}