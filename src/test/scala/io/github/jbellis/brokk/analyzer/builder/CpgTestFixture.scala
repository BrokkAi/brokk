package io.github.jbellis.brokk.analyzer.builder

import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.Cpg
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

trait CpgTestFixture[R <: X2CpgConfig[R]] extends AnyWordSpec with Matchers {

  import CpgTestFixture.*

  def project(code: String, path: String)(using config: R): MockProject[R] = 
    MockProject(config, CodeAndPath(code, path) :: Nil)

}

object CpgTestFixture {
  
  case class MockProject[R <: X2CpgConfig[R]](config: R, code: Seq[CodeAndPath]) {
    def moreCode(code: String, path: String): MockProject[R] = 
      this.copy(code = this.code :+ CodeAndPath(code, path))

    def build(using builder: IncrementalCpgBuilder[R]): Cpg = 
      builder.update(Cpg.empty, config)
  }

  case class CodeAndPath(code: String, path: String)
  
}


