package cn.xux.cloudworkbench

import cn.xux.cloudworkbench.HistoryLens.*
import cn.xux.cloudworkbench.HistoryRegion.*

internal enum class HistoryRegion(val label: String, val scope: String) {
    China("中国", "沿黄河、长江流域及后世中国，观察聚落、国家与社会的变化。"),
    Egypt("尼罗河流域", "以尼罗河下游为中心，从古埃及追踪至当代埃及。"),
    Iraq("两河流域", "沿底格里斯河和幼发拉底河，观察古代城邦、帝国与今伊拉克。"),
    India("南亚", "从今巴基斯坦的印度河城市，延伸至印度次大陆及当代印度。"),
    Britain("不列颠", "从岛上的王国、英格兰议会，追踪至近现代英国。"),
    France("法国及其前身地区", "以今法国所在区域为线索，区分法兰克王国、法国王室与共和国。"),
    Russia("俄罗斯及其前身地区", "沿莫斯科国家、俄罗斯帝国、苏联及俄罗斯联邦观察政权变迁。"),
    Germany("德意志地区", "从中欧德语地区的城市与邦国，追踪至统一后的德国。"),
    Japan("日本列岛", "从绳文聚落到近现代日本，观察列岛社会与对外联系。"),
    America("北美·美国", "从今美国境内的原住民社会，追踪至美国及其跨国联系。"),
    Brazil("巴西", "沿巴西所在地区，观察大西洋贸易、独立运动与当代国际合作。")
}

internal enum class HistoryLens(val label: String) {
    Governance("政权制度"), Economy("经济交流"), Society("社会生活"), Ideas("文化思想"), Technology("科学技术")
}

internal data class HistoryEvent(
    val region: HistoryRegion, val lens: HistoryLens, val date: String, val title: String,
    val detail: String, val reading: String? = null, val url: String? = null
)

internal data class HistoryEra(val name: String, val dates: String, val summary: String, val events: List<HistoryEvent>)

internal const val HISTORY_STANDARD = "https://phi.history.ucla.edu/nchs/world-history-content-standards/"

internal val historyEras = listOf(
    HistoryEra("聚落与生计", "史前至约公元前4000年", "定居有许多起点。有的村落依靠稻作和畜牧，有的聚落长期依靠森林与海洋。", listOf(
        HistoryEvent(Japan, Society, "约公元前13000年起", "绳文聚落依水而居", "北日本的沿海与林地为渔猎采集提供了稳定食物。遗址留下居所、墓地与仪式空间，让人看到农业出现以前的定居生活。", "联合国教科文组织 · 北日本绳文遗址", "https://whc.unesco.org/en/list/1632/"),
        HistoryEvent(India, Economy, "新石器时代", "梅尔格尔村落储粮与饲养牲畜", "今巴基斯坦的梅尔格尔发现了泥砖储藏设施、谷物与家畜遗存。沿山口移动的人群逐渐形成聚落，也发展出珠饰等手工业。", "联合国教科文组织 · 梅尔格尔", "https://whc.unesco.org/en/tentativelists/1876/"),
        HistoryEvent(China, Technology, "约公元前5000年", "河姆渡人以木构营造家园", "长江下游湿润的环境保存了木构件、骨耜和稻谷遗存。它们把当地人的建筑技术与稻作生活联系在一起。")
    )),
    HistoryEra("城市与早期文明", "约公元前4000—前1000年", "四个河流区域留下了不同的城市印记：从记录粮食的泥板，到调水的堤坝与规划整齐的街巷。", listOf(
        HistoryEvent(China, Economy, "约公元前3300—前2300年", "良渚以稻作支撑古城", "太湖流域的稻作聚落修筑堤坝、营建城址。城内外墓葬中不均等的玉器组合，为理解社会分层提供了线索。", "联合国教科文组织 · 良渚古城遗址", "https://whc.unesco.org/en/list/1592/"),
        HistoryEvent(Iraq, Technology, "约公元前3200年", "乌鲁克用泥板记账", "在今伊拉克南部，乌鲁克的大型公共建筑与早期泥板文书出现在同一座城市。记录物资收支的需要，给文字留下了早期的使用场景。", "大都会艺术博物馆 · 乌鲁克", "https://www.metmuseum.org/essays/uruk-the-first-city"),
        HistoryEvent(India, Society, "约公元前2600—前1900年", "印度河城市铺设排水系统", "摩亨佐达罗的砖屋、街道与排水设施展现出协调营建的能力。城市位于今巴基斯坦，属于跨越今天国界的哈拉帕文明。", "联合国教科文组织 · 摩亨佐达罗", "https://whc.unesco.org/en/list/138/"),
        HistoryEvent(Egypt, Governance, "约公元前26世纪", "吉萨高原建起王室金字塔", "古王国第四王朝在尼罗河西岸营建王室墓地。采石、运输、给养与施工把各类劳动者组织到同一项长期工程中。", "联合国教科文组织 · 孟菲斯及其墓地", "https://whc.unesco.org/en/list/86/"),
        HistoryEvent(China, Ideas, "约公元前13世纪起", "殷墟甲骨留下王室问卜", "晚商王室在龟甲和兽骨上留下关于收成、天气和征伐的记录。甲骨把考古发现与当时人的语言和关切连接起来。", "联合国教科文组织 · 殷墟", "https://whc.unesco.org/en/list/1114/")
    )),
    HistoryEra("古典传统与帝国", "约公元前1000年—公元300年", "大国怎样治理陌生的土地？城墙、石刻和郡县各自记录了统治者组织疆域的办法。", listOf(
        HistoryEvent(Iraq, Governance, "公元前626—前539年", "巴比伦成为帝国都城", "新巴比伦王国以巴比伦为都。宫殿、城墙与仪式大道既组织了城市空间，也在节庆与巡游中展示王权。", "联合国教科文组织 · 巴比伦", "https://whc.unesco.org/en/list/278/"),
        HistoryEvent(India, Ideas, "公元前3世纪", "阿育王把诏令刻在石上", "孔雀王朝的石柱与摩崖刻文分布在多地。君主关于行为、宽容和治理的主张，以适合当地的语言和文字向外传达。"),
        HistoryEvent(China, Governance, "公元前221年", "秦统一六国并推行郡县", "秦在兼并各国后推行郡县管理，并统一度量衡等制度。地方行政与中央之间建立起更直接的联系。"),
        HistoryEvent(Egypt, Economy, "公元前30年", "埃及纳入罗马统治", "埃及成为罗马行省。尼罗河谷的粮食经亚历山大港进入地中海运输网络，行省的征收与帝国城市的供应紧密相连。")
    )),
    HistoryEra("区域交流的扩展", "约300—1000年", "运河、都城和书籍连接远方。观察粮食、工匠与知识如何在不同政权之间流动。", listOf(
        HistoryEvent(China, Economy, "605年起", "隋代扩建大运河", "隋代分段开凿和整治水道，连接南北水系。江淮粮食和物资得以沿水路运往北方政治中心。", "联合国教科文组织 · 大运河", "https://whc.unesco.org/en/list/1443/"),
        HistoryEvent(Japan, Governance, "710年", "平城京成为都城", "奈良的新都汇集宫城、寺院和官署。街区规划与建筑既吸收东亚大陆经验，也形成列岛自身的政治和宗教中心。", "联合国教科文组织 · 古都奈良", "https://whc.unesco.org/en/list/870/"),
        HistoryEvent(France, Governance, "800年", "查理曼在罗马加冕", "法兰克国王接受皇帝冠冕。这个横跨今法国、德国等地的帝国，加深了西欧王权与罗马教会的联系。"),
        HistoryEvent(Iraq, Ideas, "9世纪", "巴格达学者译介希腊典籍", "在阿拔斯王朝的都城，学者把医学、数学和哲学作品译成阿拉伯语。叙利亚语等中介语言与宫廷资助共同推动了译书活动。"),
        HistoryEvent(Egypt, Governance, "969年", "法蒂玛王朝兴建开罗", "法蒂玛军队进入埃及后建立新的宫城。宫城、旧城与沿尼罗河的商业区逐步形成更大的城市群。")
    )),
    HistoryEra("半球贸易与交往", "约1000—1500年", "城市、商路和港口织成更密的网络。北美的土丘城市与欧亚商镇，也可以放在同一时间尺度下观察。", listOf(
        HistoryEvent(China, Economy, "11世纪初", "四川商人使用交子", "铁钱沉重，商人使用可兑换的纸质凭证。北宋随后接管发行，使地方商业实践进入官方货币管理。"),
        HistoryEvent(America, Society, "约1050年起", "卡霍基亚聚落迅速扩展", "在今伊利诺伊州，密西西比文化人群建造大型土丘、广场和居住区。当地的城市生活比欧洲殖民者抵达早了数百年。", "联合国教科文组织 · 卡霍基亚", "https://whc.unesco.org/en/list/198/"),
        HistoryEvent(Britain, Governance, "1215年", "英格兰国王接受《大宪章》", "约翰王与反叛贵族达成协议，对征收与司法作出限制。经过多次重颁，部分条款成为后世讨论君权边界的依据。"),
        HistoryEvent(Iraq, Governance, "1258年", "蒙古军队攻陷巴格达", "巴格达的阿拔斯哈里发政权终结。两河地区转入新的帝国网络，城市的政治地位和财政联系随之改变。"),
        HistoryEvent(Germany, Economy, "14世纪", "吕贝克连接汉萨商镇", "以吕贝克等城市为枢纽，商人跨越波罗的海与北海交易。共同的商业利益促成城市之间的协商和互助。", "联合国教科文组织 · 汉萨城吕贝克", "https://whc.unesco.org/en/list/272/")
    )),
    HistoryEra("跨洋联系的形成", "约1450—1770年", "远洋航路把各洲的市场拉近，也带来殖民征服、奴隶贸易与新的社会分化。", listOf(
        HistoryEvent(Brazil, Economy, "16世纪", "巴西沿海扩展蔗糖种植园", "葡萄牙殖民者在沿海建立种植园和糖坊。对外贸易的增长与强迫原住民劳动、跨大西洋贩运非洲奴隶紧密相连。"),
        HistoryEvent(India, Governance, "1526年", "莫卧儿王朝在北印度建立", "巴布尔在帕尼帕特获胜后控制德里与阿格拉。其后继者继续扩张，并在土地税收与地方精英之间建立制度联系。"),
        HistoryEvent(Russia, Governance, "1547年", "伊凡四世加冕为沙皇", "莫斯科大公采用沙皇头衔。此后向伏尔加河流域的扩张，将更多族群与贸易路线纳入莫斯科统治。"),
        HistoryEvent(China, Economy, "16世纪后期", "白银流入明代市场", "日本及美洲出产的白银经海上贸易进入中国。商人的跨海交易与赋税折银的趋势交织，加深了地方市场对外部供给的联系。"),
        HistoryEvent(Japan, Governance, "1603年", "德川幕府在江户建立", "德川家康受任征夷大将军，江户成为幕府中枢。大名领地与幕府并存，城镇和交通体系在这一格局中发展。"),
        HistoryEvent(Britain, Governance, "1689年", "英格兰议会确立《权利法案》", "法案规定王室征税和维持常备军等行为须经议会同意。王位继承与议会权力由此有了新的制度安排。")
    )),
    HistoryEra("工业化与政治革命", "约1750—1914年", "工厂改变了生产的地点，革命与独立运动重新定义了谁能以国家的名义作出决定。", listOf(
        HistoryEvent(Britain, Technology, "18世纪后期", "蒸汽动力逐渐进入工厂", "蒸汽机改进后，动力来源逐步摆脱河流位置的限制。煤炭运输、机器制造与工厂选址之间形成新的联系。"),
        HistoryEvent(America, Governance, "1776年", "十三个殖民地宣布独立", "大陆会议通过《独立宣言》，公开陈述脱离英国的理由。战争仍在继续，美国的联邦制度在此后逐步形成。"),
        HistoryEvent(France, Ideas, "1789年", "法国通过《人权和公民权宣言》", "制宪议会以自由、财产和国民主权阐述政治权利。妇女与殖民地居民的实际处境，也暴露出普遍权利主张的界限。"),
        HistoryEvent(Brazil, Governance, "1822年", "巴西宣布独立", "佩德罗宣布巴西脱离葡萄牙，国家采取帝制。独立改变了政治主权，奴隶制则延续至1888年。"),
        HistoryEvent(China, Economy, "1842年", "《南京条约》规定五口通商", "第一次鸦片战争后，清政府被迫与英国签订条约。广州、厦门、福州、宁波和上海被列为通商口岸，沿海贸易格局由此改变。"),
        HistoryEvent(Japan, Governance, "1868年", "明治维新开始", "新政府以天皇名义重组政权，随后推动废藩置县、征兵与兴办学校。地方治理和日常生活都受到影响。"),
        HistoryEvent(Egypt, Economy, "1869年", "苏伊士运河通航", "运河连通地中海与红海，缩短欧洲至印度洋的海运航程。埃及在国际航运与列强竞争中的位置随之改变。"),
        HistoryEvent(Germany, Governance, "1871年", "德意志帝国成立", "普鲁士国王在凡尔赛获拥立为德意志皇帝。多个邦国纳入联邦结构，普鲁士在新国家中居于主导地位。")
    )),
    HistoryEra("世界战争与社会危机", "约1900—1945年", "革命、战争与经济萧条改变了政治秩序。各地的民众也以救济、抗争和组织动员回应危机。", listOf(
        HistoryEvent(China, Governance, "1911—1912年", "辛亥革命与清帝退位", "武昌起义后，多省宣布独立。中华民国临时政府成立与清帝退位，标志着中国国家制度的一次转折。"),
        HistoryEvent(France, Society, "1914年", "第一次世界大战波及法国北部", "德军经比利时向法国推进。西线的阵地战持续多年，动员、流离失所和长期的物资征集改变了前线及后方社会。"),
        HistoryEvent(Russia, Governance, "1917年", "俄国发生两次革命", "二月革命终结沙皇统治，十月革命中布尔什维克夺取政权。战争、土地分配和工人生活贯穿政治冲突。"),
        HistoryEvent(India, Society, "1930年", "食盐进军挑战殖民盐税", "甘地及追随者步行抵达海岸制盐。围绕日常必需品的非暴力抗争，吸引了更广泛的民众参与独立运动。"),
        HistoryEvent(America, Economy, "1933年起", "美国推行新政", "在大萧条期间，联邦政府通过公共工程、银行监管与救济计划应对失业和金融危机。"),
        HistoryEvent(Germany, Society, "1933—1945年", "纳粹迫害与犹太人大屠杀", "纳粹德国及其合作者逐步将犹太人排除出公共生活，继而实施驱逐和系统性杀戮。约六百万犹太人遇害。", "美国大屠杀纪念馆 · 大屠杀概述", "https://encyclopedia.ushmm.org/content/en/article/introduction-to-the-holocaust")
    )),
    HistoryEra("战后与当代世界", "1945年至今", "从民族独立、冷战与欧洲重组，到太空探索和气候谈判，国家之间的竞争与合作并行展开。", listOf(
        HistoryEvent(America, Governance, "1945年", "《联合国宪章》在旧金山签署", "来自不同地区的代表在美国旧金山签署宪章。宪章于同年10月生效，联合国正式成立，为战后国际合作建立了常设机构。", "联合国 · 宪章", "https://www.un.org/en/about-us/un-charter"),
        HistoryEvent(India, Governance, "1947年", "英属印度分治并独立", "印度与巴基斯坦成为两个独立自治领。新边界伴随大规模迁徙与暴力，长期影响南亚的国家关系。"),
        HistoryEvent(Egypt, Economy, "1956年", "埃及将苏伊士运河公司国有化", "纳赛尔宣布国有化后，英、法、以色列对埃及采取军事行动。运河的控制权成为去殖民化与冷战外交的交汇点。"),
        HistoryEvent(Japan, Technology, "1964年", "东海道新干线开通", "东京与大阪之间开行高速列车。专用线路、列车与信号系统配合，改变了沿线城市的日常往来。"),
        HistoryEvent(America, Technology, "1969年", "阿波罗11号载人登月", "两名宇航员在月面活动并采回样品，一名宇航员留在月球轨道。太空竞赛也为月球带回了可在地球实验室研究的实物。"),
        HistoryEvent(China, Economy, "1978年起", "中国开启改革开放", "中共十一届三中全会后，农村经营方式和对外经济政策陆续调整。经济特区随后成为引入外资和试验制度的窗口。"),
        HistoryEvent(Germany, Governance, "1990年", "德国实现统一", "民主德国加入联邦德国，冷战中形成的两德分立格局结束。法律制度、公共财政与产业随后经历长期整合。"),
        HistoryEvent(Russia, Governance, "1991年", "苏联解体", "苏联各加盟共和国相继独立。俄罗斯联邦承接苏联在联合国的席位，欧亚地区的政治边界由此重组。"),
        HistoryEvent(Britain, Governance, "1998年", "《贝尔法斯特协议》达成", "英国、爱尔兰两国政府与北爱尔兰主要政治力量促成协议，为权力分享及跨境合作提供制度框架。"),
        HistoryEvent(France, Governance, "2015年", "各方在巴黎通过气候协定", "联合国气候变化大会在巴黎通过《巴黎协定》。各方以自主贡献提出减排目标，并按约定周期报告和更新行动。", "联合国气候变化框架公约 · 巴黎协定", "https://unfccc.int/process-and-meetings/the-paris-agreement"),
        HistoryEvent(Iraq, Ideas, "2019年", "巴比伦列入世界遗产", "伊拉克的巴比伦遗址获列入世界遗产名录。考古遗迹、近现代重建与保护工作叠加在同一处历史景观中。", "联合国教科文组织 · 巴比伦", "https://whc.unesco.org/en/list/278/"),
        HistoryEvent(India, Technology, "2023年", "月船三号在月球南部高纬着陆", "着陆器于8月23日实现软着陆，巡视器随后探测着陆点附近的月面。印度由此获得该区域的原位观测资料。", "印度空间研究组织 · 月船三号", "https://www.isro.gov.in/Release_of_Chandrayaan-3_science_data_to_the_Scientific_Community.html"),
        HistoryEvent(China, Technology, "2024年", "嫦娥六号带回月背样品", "返回器于6月25日带回在月球背面采集的样品。研究者首次能够在地球实验室直接分析来自月背的材料。", "国家航天局 · 嫦娥六号样品交接", "https://www.cnsa.gov.cn/n6758823/n6758844/n10518102/n10518147/c10567320/content.html"),
        HistoryEvent(Brazil, Governance, "2025年", "贝伦承办联合国气候大会", "各方在巴西贝伦举行第三十届联合国气候变化大会，围绕气候行动的落实、资金和适应问题展开谈判。", "联合国气候变化框架公约 · 贝伦会议成果", "https://unfccc.int/cop30/belem-political-package"),
        HistoryEvent(America, Society, "2026年", "北美三国合办世界杯", "加拿大、墨西哥和美国共同举办男子足球世界杯。参赛队扩大至48支，赛事分布于三国的16座城市。", "国际足联 · 2026年世界杯", "https://www.fifa.com/en/tournaments/mens/worldcup/canadamexicousa2026/articles/fifa-world-cup-2026-hosts-cities-dates-usa-mexico-canada")
    ))
)
