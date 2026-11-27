package cn.xux.cloudworkbench

internal data class TrailSource(val label: String, val url: String)
internal data class TrailStep(val date: String, val title: String, val body: String, val source: TrailSource)
internal data class HistoryTrail(val title: String, val span: String, val subjects: String, val introduction: String,
    val steps: List<TrailStep>, val newsStatus: String? = null)

internal object HistoryTrails {
    private val diarySource = TrailSource("安妮·弗兰克之家 · 日记与手稿",
        "https://www.annefrank.org/en/anne-frank/diary/complete-works-anne-frank/")
    private val testimonySource = TrailSource("安妮·弗兰克之家 · 从写作到出版",
        "https://www.annefrank.org/en/anne-frank/go-in-depth/anne-diarist-icon/")
    val diary = HistoryTrail("安妮的日记怎样走出密室", "1942—1947", "安妮·弗兰克、米普·吉斯与日记手稿",
        "在德军占领下的阿姆斯特丹，一名犹太少女为自己写下的文字，怎样在战后抵达读者手中？", listOf(
            TrailStep("1942年6—7月", "从生日礼物写起",
                "安妮十三岁生日时得到日记本。几周后，为躲避针对犹太人的迫害，全家藏进父亲公司后面的密室。", testimonySource),
            TrailStep("1944年春", "为战后的读者改写",
                "听到广播呼吁保存占领时期的生活记录后，安妮着手重写早先的日记。她既是见证人，也是为出版修改手稿的作者。", diarySource),
            TrailStep("1944年8月", "写作中断，手稿获救",
                "最后一则日记写于8月1日。三天后，藏身者被捕。米普·吉斯与贝普·福斯奎尔随后发现了留在密室的纸页。", testimonySource),
            TrailStep("1945年", "日记回到父亲手中",
                "得知安妮在战争中遇难后，米普把保存的手稿交给她的父亲奥托。家人的去向和女儿想出版一本书的愿望，在这里交汇。", testimonySource),
            TrailStep("1947年6月25日", "荷兰语初版问世",
                "奥托整理女儿留下的不同版本，书在阿姆斯特丹出版。读者由此能够从一名少女的日常生活认识纳粹迫害。", diarySource)
        ))

    private val canalSource = TrailSource("苏伊士运河管理局 · 运河史",
        "https://www.suezcanal.gov.eg/English/About/SuezCanal/Pages/CanalHistory.aspx")
    private val suezSource = TrailSource("美国国务院历史办公室 · 苏伊士危机",
        "https://history.state.gov/milestones/1953-1960/suez")
    private val clearanceSource = TrailSource("联合国 · 运河清障行动",
        "https://dppa.un.org/en/united-nations-suez-canal-clearance-operation-unsco-1956-1957")
    private val leagueSource = TrailSource("广东省档案馆 · 中国同盟会",
        "https://www.da.gd.gov.cn/portal_home/wap/archivesDetail/4087")
    private val revolutionSource = TrailSource("全国政协 · 辛亥革命史料展",
        "https://www.cppcc.gov.cn/zxww/xinhai100/shouye/index.shtml")
    private val marchSource = TrailSource("甘地遗产门户 · 食盐进军前后",
        "https://www.gandhiheritageportal.org/background-to-the-salt-satyagraha")
    private val saltSource = TrailSource("甘地遗产门户 · 甘地大事年表",
        "https://www.gandhiheritageportal.org/ml/eventcontentdetail/OA%3D%3D/NzQxOQ%3D%3D")
    private val pactSource = TrailSource("甘地遗产门户 · 德里谈判纪事",
        "https://www.gandhiheritageportal.org/fr/gandhi-irwin-pact")
    private val apolloSource = TrailSource("NASA · 阿波罗计划任务档案",
        "https://www.nasa.gov/the-apollo-program/")
    private val moonSource = TrailSource("NASA · 阿波罗11号任务",
        "https://www.nasa.gov/missions/apollo/apollo-11/apollo-11-mission-overview/")
    private val pledgeSource = TrailSource("NASA · 登月目标与阿波罗11号",
        "https://www.nasa.gov/mission/apollo-11/")
    private val changeSource = TrailSource("国家航天局 · 嫦娥六号任务纪实",
        "https://www.cnsa.gov.cn/n6758823/n6758844/n10518102/n10518147/c10565180/content.html")
    private val landingSource = TrailSource("国家航天局 · 嫦娥六号着陆月背",
        "https://www.cnsa.gov.cn/n6758823/n6758844/n10518102/n10518147/c10541444/content.html")
    private val samplesSource = TrailSource("国家航天局 · 月球样品交接",
        "https://www.cnsa.gov.cn/n6758823/n6758844/n10518102/n10518147/c10567320/content.html")
    private val parisSource = TrailSource("联合国气候变化框架公约 · 巴黎协定",
        "https://unfccc.int/process-and-meetings/the-paris-agreement")
    private val dubaiSource = TrailSource("联合国气候变化框架公约 · 迪拜大会成果",
        "https://unfccc.int/cop28/5-key-takeaways")
    private val belemSource = TrailSource("联合国气候变化框架公约 · 贝伦决定汇编",
        "https://unfccc.int/cop30/belem-political-package")
    private val antalyaSource = TrailSource("联合国气候变化框架公约 · 安塔利亚闭幕通报",
        "https://unfccc.int/news/cop31-delivers-important-progress-across-negotiations-and-action-agenda-advancing-implementation-but")
    private val bidSource = TrailSource("国际足联 · 联合申办表决结果",
        "https://inside.fifa.com/en/tournaments/mens/worldcup/canadamexicosa2026/media-releases/canada-mexico-and-usa-selected-as-hosts-of-the-2026-fifa-world-cuptm")
    private val venuesSource = TrailSource("国际足联 · 主办城市与赛程",
        "https://www.fifa.com/en/tournaments/mens/worldcup/canadamexicosa2026/articles/fifa-world-cup-2026-hosts-cities-dates-usa-mexico-canada")
    private val scoresSource = TrailSource("国际足联 · 逐场比赛结果",
        "https://www.fifa.com/en/tournaments/mens/worldcup/canadamexicosa2026/articles/match-schedule-fixtures-results-teams-stadiums")
    private val finalSource = TrailSource("国际足联 · 决赛与颁奖记录",
        "https://inside.fifa.com/organisation/news/new-york-jersey-stadium-spain-world-champions-mbappe-haaland")

    val suez = HistoryTrail("一条运河的开凿与收回", "1854—1957", "苏伊士运河、德·雷赛普与纳赛尔",
        "从开凿特许到危机后复航，埃及与运河公司的关系如何改变？", listOf(
            TrailStep("1854年11月30日", "德·雷赛普取得特许",
                "埃及当局准许他设立公司，筹办贯通地中海与红海的航道。", canalSource),
            TrailStep("1859—1869年", "从开工到通航",
                "工人从塞得港一端开挖。十年后运河通航，欧洲与印度洋之间的船只多了一条穿过埃及的航路。", canalSource),
            TrailStep("1956年7月26日", "埃及接管运河公司",
                "纳赛尔宣布公司国有化，将通行费收入与阿斯旺大坝的建设联系起来。", suezSource),
            TrailStep("1956年10—11月", "运河陷入危机",
                "以色列、英国和法国先后对埃及采取军事行动。航道受阻，围绕公司所有权的争端演变为国际危机。", suezSource),
            TrailStep("1957年4月", "航道清障完成",
                "联合国组织的打捞船队完成清障，埃及重新运营航道。至此结束的是这次停航，运河的历史仍在继续。", clearanceSource)
        ))

    val republic = HistoryTrail("孙中山与临时政府的成立", "1905—1912", "孙中山、武昌起义者与袁世凯",
        "从结社到交接临时总统职位，谁在不同关口改变了政权的走向？", listOf(
            TrailStep("1905年8月20日", "同盟会在东京成立",
                "孙中山、黄兴等人联合多个革命团体的成员，将分散的组织联结起来。", leagueSource),
            TrailStep("1911年10月10日", "武昌起义",
                "湖北革命团体和新军中的起义者发动起义。随后多省响应，建立新政权成为各省代表面前的问题。", revolutionSource),
            TrailStep("1912年1月1日", "南京临时政府成立",
                "孙中山在南京就任临时大总统。此时清廷仍在北京，共和政府的成立与清帝退位之间相隔一个多月。", revolutionSource),
            TrailStep("1912年2月12日", "清帝退位",
                "清廷颁布退位诏书。南北谈判随之转向临时总统人选与权力交接。", revolutionSource),
            TrailStep("1912年4月1日", "孙中山卸任",
                "袁世凯此前已在北京就职，孙中山在南京正式卸任。南京临时政府的这一段历程至此结束。", revolutionSource)
        ))

    val gandhi = HistoryTrail("甘地如何把食盐变成公共议题", "1930—1931", "甘地、欧文与食盐",
        "从一封信、一段步行到一份临时协议，看一种日常必需品如何把民众带入政治谈判。", listOf(
            TrailStep("1930年3月2日", "写信说明为何选择盐法",
                "甘地致信总督欧文，说明盐税给民众带来的负担，并预告将以公开违抗盐法的方式提出抗议。", marchSource),
            TrailStep("1930年3月12日", "从修行院步行出发",
                "甘地和同行者离开萨巴尔马蒂修行院，向海边的丹迪行进。村庄成为人们沿途参加和见证抗议的场所。", marchSource),
            TrailStep("1930年4月6日", "在丹迪违抗盐法",
                "抵达海岸后，甘地以取盐公开挑战殖民地盐法，让原本抽象的税制争议成为人人可以理解的行动。", saltSource),
            TrailStep("1931年3月5日", "签署临时协议",
                "甘地与欧文在德里签署协议，这一轮公民不服从运动暂停，印度独立问题仍待解决。", pactSource)
        ))

    val apollo = HistoryTrail("从登月承诺到最后一次月面考察", "1961—1972", "肯尼迪、阿姆斯特朗与阿波罗飞船",
        "沿着载人登月目标，看地球上的政治承诺怎样变成绕月试飞、登陆和月面科学考察。", listOf(
            TrailStep("1961年5月25日", "提出十年内载人登月",
                "肯尼迪向国会提出目标，要在这个十年结束前把人送上月球并安全带回。返航从一开始就是目标的一部分。", pledgeSource),
            TrailStep("1968年12月", "阿波罗8号载人绕月",
                "三名宇航员乘指令舱进入月球轨道。飞船验证了人可以往返地月之间，登月器尚未着陆。", apolloSource),
            TrailStep("1969年7月", "阿波罗11号首次登陆",
                "阿姆斯特朗和奥尔德林在月面采样，柯林斯留在轨道接应。三人会合后携带样品返回地球。", moonSource),
            TrailStep("1972年12月", "阿波罗17号完成最后一次登月",
                "塞尔南与地质学家施密特在月面工作，埃文斯留在轨道。任务返航后，阿波罗系列的载人月面考察结束。", apolloSource)
        ))

    val changeSix = HistoryTrail("一份月背样品如何抵达地球", "2024年5—6月", "嫦娥六号、鹊桥二号与月背样品",
        "跟随样品经过着陆器、上升器和返回器，直到移交给地面的研究团队。", listOf(
            TrailStep("2024年5月3日", "从文昌启程",
                "嫦娥六号进入地月转移轨道，携带分工不同的探测器组件飞往月球。", changeSource),
            TrailStep("2024年6月2日", "在月球背面着陆",
                "鹊桥二号中继星提供通信支持。着陆器与上升器组合体在南极－艾特肯盆地着陆，展开月背采样。", landingSource),
            TrailStep("2024年6月4—6日", "样品从月面转交至轨道",
                "上升器携样品离开月面，随后与轨道器和返回器组合体交会对接，样品容器被转移到返回器。", changeSource),
            TrailStep("2024年6月25日", "返回器在内蒙古着陆",
                "返回器飞抵四子王旗，完成首次月背采样返回。随后交接的样品重1935.3克，成为可供实验室分析的月背材料。", samplesSource)
        ))

    val climate = HistoryTrail("《巴黎协定》怎样盘点全球行动", "2015年至今", "《巴黎协定》、缔约方与全球盘点",
        "各国分别提交气候行动，怎样判断合起来离共同目标还有多远？", listOf(
            TrailStep("2015年12月12日", "协定在巴黎通过",
                "各方约定以国家自主贡献提出本国行动，并定期盘点全球整体进展。", parisSource),
            TrailStep("2016年11月4日", "协定生效",
                "生效条件达到，协定从谈判文本进入实施阶段。各国要持续提交并更新本国贡献。", parisSource),
            TrailStep("2023年12月", "迪拜完成首次全球盘点",
                "各方检视集体进展，呼吁在能源系统中向摆脱化石燃料转型。盘点结果为下一轮国家行动提供依据。", dubaiSource),
            TrailStep("2025年11月", "贝伦讨论怎样衡量适应进展",
                "会议就全球适应目标等事项作出决定，把适应气候影响及其衡量方式带入后续工作。", belemSource),
            TrailStep("2026年11月22日", "安塔利亚启动第二次全球盘点",
                "大会闭幕，第二次盘点启动。各方在适应与资金等事项上取得进展，主席国推动的综合决定未获一致同意。按计划，盘点成果将在2028年讨论。", antalyaSource)
        ), newsStatus = "进展截至2026年11月22日")

    val worldCup = HistoryTrail("北美世界杯从联合申办到决赛", "2018—2026", "加拿大、墨西哥、美国与48支参赛队",
        "从三国联合申办，到墨西哥城开球和美国决赛，跟随一届跨国赛事的举办过程。", listOf(
            TrailStep("2018年6月13日", "联合申办获选",
                "国际足联大会投票将主办权交给加拿大、墨西哥和美国，48队参赛的世界杯有了三个东道主。", bidSource),
            TrailStep("2024年2月4日", "揭幕战与决赛场地确定",
                "公开赛程将揭幕战安排在墨西哥城，决赛安排在纽约／新泽西赛区。", venuesSource),
            TrailStep("2026年6月11日", "墨西哥城揭幕",
                "墨西哥队以2比0战胜南非队，三国16座城市接续承办本届赛事。", scoresSource),
            TrailStep("2026年7月19日", "西班牙在加时赛夺冠",
                "决赛在纽约／新泽西赛区举行。西班牙队加时以1比0击败阿根廷队，队长罗德里举起奖杯。", finalSource)
        ), newsStatus = "已于2026年7月19日结束")
}
