package cn.xux.cloudworkbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal data class PostwarPeriod(
    val start: Int, val end: Int?, val title: String, val context: String, val culture: String
) {
    val dates = if (end == null) "${start}年至今" else "${start}—${end}"
    fun contains(event: HistoryEvent) = event.date.take(4).toInt() in start..(end ?: Int.MAX_VALUE)
}

internal val postwarPeriods = listOf(
    PostwarPeriod(1945, 1959, "重建家园与民族独立",
        "国际制度重建与殖民秩序瓦解同时发生。南亚分治让许多家庭越过新国界。英国建立国民医疗服务后又从加勒比地区招募医护，帝国时代的迁移路线延续到战后的医院里。",
        "印度导演萨蒂亚吉特·雷伊让观众跟随孟加拉乡村的姐弟看世界。广岛的幸存者则以遗物和证言保存原爆记忆。电影与博物馆留下了两种尺度的战后生活。"),
    PostwarPeriod(1960, 1979, "群众运动与发展道路",
        "新干线和登月计划展示国家可以怎样集中资源。另一种变化发生在街头和考场。美国民众把工作机会与种族平等一起带上游行标语，中国恢复统一高考后，不同年龄的考生重返考场。",
        "华盛顿游行把演说、歌唱和政治诉求带到同一现场。巴西艺术家奥伊蒂西卡让观众走进装置，身体、脚下的沙和城市居住经验也成为作品的一部分。"),
    PostwarPeriod(1980, 1991, "社会记忆与冷战终结",
        "德国统一、苏联解体改变了国家边界与政治体制。在制度变动的大事记之外，病患亲友、写作者和街头艺术家也在改变谁能进入公共记忆。",
        "开罗街区在马哈福兹的小说中有了跨代的叙述。美国亲友把因艾滋病去世者的名字缝进被面。柏林墙开放后，画家又把边界设施变成露天画廊。"),
    PostwarPeriod(1992, 2007, "转型社会与跨国交流",
        "南非举行全民参与的民主选举，随后通过公开听证处理种族隔离时期的侵害。印度以学校膳食支持儿童入学；北爱尔兰则以权力分享回应长期冲突。",
        "中国昆曲、伊拉克玛卡姆和巴西巴伊亚的圆圈桑巴，都依靠表演者与听众共同维系。国际认定留下了档案，而唱腔、节奏与身段仍要靠人传给人。"),
    PostwarPeriod(2008, 2019, "金融震荡与文化遗产保护",
        "金融危机冲击就业和贸易。气候谈判、城市重建与文化遗产保护并行展开。巴黎的国家间谈判与摩苏尔当地人的重建工作，涉及截然不同的参与者和时间尺度。",
        "日本家庭通过年节备餐传递有关季节和款待的知识。伊拉克的古城遗址和经历战火的现代街区，则提出另一道问题：由谁决定应当保存什么？"),
    PostwarPeriod(2020, null, "疫情之后的生活与智能技术",
        "新冠疫情使照护与公共卫生成为家庭共同面对的问题。同期，月球探测和大模型取得进展。读具体事件时，可以分辨技术的实验成果、制度的部署，以及人们真正接触到它们的时间。",
        "中国制茶、待客和师徒传习进入非遗档案。法国作家埃尔诺以个人记忆书写阶层与性别。对读这些材料，可以在疫情和算法的年表之外看见身体、劳动与家庭。")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PostwarPeriodPanel(selected: PostwarPeriod?, choose: (PostwarPeriod?) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("战后世界分期", Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.titleLarge)
            if (selected != null) TextButton(onClick = { choose(null) }) { Text("返回六期总览") }
        }
        if (selected == null) {
            Text("按重建、社会转型和文化生活选取六个观察时段。各地转折发生的年份并不一致。", color = Muted,
                style = MaterialTheme.typography.bodyMedium)
            PostwarIndex { choose(it) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                postwarPeriods.forEach { period ->
                    FilterChip(period == selected, onClick = { choose(period) }, label = { Text(period.dates) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = Outline)
            Text("文化与日常生活", color = Accent, style = MaterialTheme.typography.labelLarge)
            Text(selected.culture, color = Ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable internal fun PostwarIndex(currentStart: Int = 0, choose: (PostwarPeriod) -> Unit) {
    for (period in postwarPeriods) {
        HorizontalDivider(color = Outline)
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { choose(period) }
            .heightIn(min = 48.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(period.dates, color = Accent, style = MaterialTheme.typography.bodySmall)
                Text(period.title, color = Ink, style = MaterialTheme.typography.titleMedium)
            }
            if (currentStart == period.start) Icon(Icons.Outlined.Check, "当前时段", tint = Accent)
            else Icon(Icons.Outlined.ChevronRight, null, tint = Accent)
        }
    }
}
