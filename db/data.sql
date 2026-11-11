USE storybook;

INSERT IGNORE INTO sys_role (role_code, role_name) VALUES
  ('ROLE_USER', '普通用户'), ('ROLE_ADMIN', '管理员');

-- BCrypt hash for Admin@123 (cost 10)
INSERT IGNORE INTO sys_user (username, password, nickname, email, status)
VALUES ('admin', '$2b$10$eERqcFIgw3MJPzSFB.SiNeRNsqPoglZfDfKs0LjbnSDktTX/WpLnm', '平台管理员', 'admin@storybook.local', 0);
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u JOIN sys_role r ON r.role_code = 'ROLE_ADMIN' WHERE u.username = 'admin';

INSERT IGNORE INTO style_dict (name, prompt_fragment, sort, status) VALUES
 ('卡通', 'bright cute cartoon illustration, rounded shapes, candy colors, clean outlines', 1, 0),
 ('水彩', 'soft watercolor children book illustration, translucent washes, paper texture', 2, 0),
 ('油画', 'friendly storybook oil painting, visible brush strokes, warm light', 3, 0),
 ('剪纸', 'layered paper-cut illustration, bold simple silhouettes, colorful paper texture', 4, 0),
 ('3D 渲染', 'cute 3D rendered storybook scene, soft studio lighting, toy-like characters', 5, 0),
 ('像素', 'cute pixel art storybook illustration, readable shapes, limited candy palette', 6, 0);

INSERT IGNORE INTO preset_character (name, category, avatar_url, appearance_prompt, description, sort, status) VALUES
 ('小熊糯米','ANIMAL','/files/presets/bear-nuomi.png','cute chubby cream bear, round ears, red scarf, cartoon full body, clean background','温柔可靠的小熊',1,0),
 ('兔兔露露','ANIMAL','/files/presets/rabbit-lulu.png','cute white rabbit girl, pink ears, yellow dress, cartoon full body, clean background','活泼的小兔子',2,0),
 ('小狐狸橘子','ANIMAL','/files/presets/fox-orange.png','cute orange fox child, white chest, blue backpack, cartoon full body, clean background','好奇的小狐狸',3,0),
 ('星星龙阿光','FANTASY','/files/presets/dragon-aguang.png','tiny friendly mint dragon, star-shaped freckles, purple wings, cartoon full body, clean background','会发光的小龙',4,0),
 ('独角兽彩虹','FANTASY','/files/presets/unicorn-rainbow.png','cute pastel unicorn, rainbow mane, golden horn, cartoon full body, clean background','彩虹独角兽',5,0),
 ('小男孩乐乐','BOY','/files/presets/boy-lele.png','six year old Chinese boy, short black hair, yellow hoodie, blue shorts, cartoon full body, clean background','勇敢的小探险家',6,0),
 ('小女孩朵朵','GIRL','/files/presets/girl-duoduo.png','seven year old girl, bob haircut, pink dress, yellow rain boots, cartoon full body, clean background','喜欢提问的小女孩',7,0),
 ('机器人七七','OTHER','/files/presets/robot-77.png','small rounded helper robot, sky blue body, heart display, cartoon full body, clean background','贴心的机器人朋友',8,0),
 ('小企鹅冰冰','ANIMAL','/files/presets/penguin-bing.png','cute penguin child, navy body, orange scarf, cartoon full body, clean background','怕冷但勇敢',9,0),
 ('海盗船长豆豆','OTHER','/files/presets/pirate-doudou.png','cute child pirate captain, red hat, striped shirt, cartoon full body, clean background','想发现宝藏',10,0),
 ('森林精灵艾叶','FANTASY','/files/presets/elf-aiye.png','cute forest elf child, green hood, leaf staff, cartoon full body, clean background','守护森林的精灵',11,0),
 ('小女孩安安','GIRL','/files/presets/girl-anan.png','five year old girl, two pigtails, mint overalls, orange shoes, cartoon full body, clean background','爱唱歌的小女孩',12,0);

INSERT IGNORE INTO sensitive_word (word, category, level, status) VALUES
 ('敏感词测试','OTHER','HIGH',0), ('暴力示例','暴力','NORMAL',0), ('违规内容','OTHER','HIGH',0);

INSERT IGNORE INTO ai_model_config (config_key, base_url, model_name, temperature, max_tokens, params) VALUES
 ('CHAT','https://api.deepseek.com/v1','deepseek-chat',0.70,4096,'{"supportsStructuredOutput":true}'),
 ('IMAGE','https://api-inference.modelscope.cn/','Qwen/Qwen-Image-2.1',0.80,0,'{"width":1024,"height":1024,"supportsImageReference":true}');

INSERT IGNORE INTO prompt_template (template_key, name, content, remark) VALUES
 ('story_gen','故事生成','你是一位儿童绘本作家。请根据主题 {{theme}}，为 {{ageGroup}} 年龄段创作 {{pageCount}} 页绘本。风格：{{storyStyle}}。配文语言为 {{textLang}}，title、coverText、storyText 必须全部使用该语言，不能混用另一种语言。输出严格 JSON：{"title":"","coverText":"","pages":[{"pageNo":1,"storyText":""}]}。','故事生成结构化输出'),
 ('character_extract','角色提取','从以下故事中提取所有重要角色：{{story}}。返回 JSON 数组，每项包含 name、genderAge、appearance、clothing、features。','自动提取角色'),
 ('character_polish','角色描述润色','将角色信息整理为适合图像模型的完整卡通外观描述：{{character}}。只输出描述文本。','自定义角色完善'),
 ('character_image_gen','角色参考图','将结构化角色信息 {{character}} 转为英文视觉提示词。保留年龄、物种、脸部、颜色、服装、配饰和特征；生成干净背景上的全身站姿、正面和小侧面/背面视图，便于后续场景保持一致。不要文字、水印或无关角色。只输出提示词。','参考图生成'),
 ('storyboard_gen','分镜生成','为输入绘本的全部页面生成分镜：{{story}}。输出 JSON 对象 {"pages":[{"pageNo":1,"scenePrompt":"英文画面描述","summary":"{{textLang}}摘要","characters":[角色ID]}]}。每页出现一次，角色 ID 必须来自输入角色卡，保持物种、外观、服装一致，只描绘对应页面原文中的动作、物体和场景。','分镜生成'),
 ('image_prompt','插画提示词','故事原文：{{storyText}}。页面摘要：{{summary}}。已有分镜：{{scenePrompt}}。插画风格：{{imageStyle}}。出场角色结构化信息：{{characters}}。保留故事中的每个具体动作、物体、地点和情绪，并让角色执行原文动作；只输出英文画面提示词，不增加无关事件，不要文字、字母、标志或水印。','插画生成'),
 ('auto_quality_check','一键质量检查','检查以下绘本是否有 {{pageCount}} 页、是否全部使用 {{textLang}}、是否符合 {{ageGroup}} 年龄难度并具有完整故事结构。只输出 JSON：{"pass":true,"issues":[]}。内容：{{story}}','一键创作质量检查');
