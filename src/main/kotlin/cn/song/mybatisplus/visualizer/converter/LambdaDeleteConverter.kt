package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.project.Project

/**
 * LambdaDeleteWrapper 转换器（F-10，MP 3.5+）：`DELETE FROM 表 WHERE ...`。
 *
 * 全部调用均为条件（eq 等），复用 [QueryWrapperParser] 解析。
 *
 * 无 WHERE 条件时 conditions 为空，UI 层需红色警示"删除全表数据"风险。
 */
object LambdaDeleteConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.LAMBDA_DELETE)

    override fun convert(chain: WrapperChain, table: TableInfo, project: Project?): SqlStatement {
        val conditions = QueryWrapperParser(table).parse(chain).conditions
        return DeleteStatement(table, conditions)
    }
}
