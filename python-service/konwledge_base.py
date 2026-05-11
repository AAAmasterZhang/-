import hashlib
import os
from langchain_chroma import Chroma
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from datetime import datetime
import config_data as config

# ========== 新增：txt文件存储目录配置（建议放到config_data.py） ==========
# config.py中添加：
# TXT_STORE_PATH = "./blog_txts"
# os.makedirs(config.TXT_STORE_PATH, exist_ok=True)

def check_md5(md5_str: str):
    """检查传入的md是否已经被处理过了"""
    if not os.path.exists(config.md5_path): #不存在文件肯定是没有处理
        open(config.md5_path,'w',encoding="utf-8").close()  #创建空文件
        return False
    else:
        #读取所有行
        for line in open(config.md5_path,'r',encoding="utf-8").readlines():
            line = line.strip() # 去掉首尾的换行符
            if line == md5_str:
                return True
        return False

def save_md5(md5_str: str):
    """
    保存传入的md5到文件内
    每一行保存一个md5值，值之间用换行符隔开
    """
    with open(config.md5_path,'a',encoding="utf-8") as f:   #追加
        f.write(md5_str+'\n')

def get_string_md5(input_str: str,encoding: str = 'utf-8') -> str:
    """
    将传入的字符串转换为md5值
    :param input_str: 输入的字符串
    :param encoding: 编码方式，默认utf-8
    :return: md5值的16进制字符串
    """
    #将字符串转换为字节数组
    str_bytes = input_str.encode(encoding)
    #创建md5对象
    md5_obj = hashlib.md5()     # 创建md5对象
    md5_obj.update(str_bytes)
    md5_hex = md5_obj.hexdigest()   #得到md5的16进制字符串
    return md5_hex

# ========== 新增：自动保存博客到txt文件（适配多评论） ==========
def save_blog_to_txt(blog_id: str, title: str, content: str, comments: list):
    """
    将博客（含多条评论）保存为txt文件
    :param blog_id: 博客唯一ID
    :param title: 博客标题
    :param content: 博客内容
    :param comments: 评论列表（如["好吃！", "性价比高"]）
    :return: txt文件路径、拼接后的完整文本
    """
    # 1. 拼接评论（每行一条）
    comments_str = "\n".join(comments) if comments else "无评论"
    # 2. 构造txt完整内容（标题+内容+多条评论）
    full_content = f"{title}\n{content}\n{comments_str}".strip()
    # 3. 构造txt文件路径
    txt_filename = f"blog_{blog_id}.txt"
    txt_path = os.path.join(config.TXT_STORE_PATH, txt_filename)
    # 4. 写入/覆盖txt文件
    os.makedirs(config.TXT_STORE_PATH, exist_ok=True)
    with open(txt_path, 'w', encoding='utf-8') as f:
        f.write(full_content)
    return txt_path, full_content

class KnowledgeBaseService(object):
    """两个属性一个方法，从外部拿到MD5，判定是否需要存入向量库"""
    os.makedirs(config.persist_directory, exist_ok=True)  # 确保目录存在,如果不存在则创建
    def __init__(self):
        self.chrom = Chroma(
            collection_name=config.collection_name, #表名
            embedding_function=DashScopeEmbeddings(model="text-embedding-v4"),
            persist_directory=config.persist_directory  #数据库本地存储文件夹
        )  # 知识库的向量数据库

        self.spliter = RecursiveCharacterTextSplitter(
            chunk_size=config.chunk_size,   #分割后文本的最大长度
            chunk_overlap=config.chunk_overlap,   #分割后文本的重叠长度
            separators=config.separators,    # 分割符
            length_function=len,     # 计算文本长度的函数
        )  # 知识库的文本分割器

    def upload_by_str(self,data: str,filename):
        """
        将传入的字符串进行向量化，存入向量数据库中
        转为16进制--判断是否处理过--判断是否分割--加载到向量库--保存到md5文件（用于判断是否重复的文件）
        :param data: 输入的字符串
        :param filename: 文件名，用于标记来源
        :return: 上传结果
        """
        # ========== 新增：上传前删除该文件的旧数据（精准更新核心） ==========
        old_docs = self.chrom.get(where={"source": filename})
        '''返回类似这样的字典
        {
            "ids": [
                "id1",
                "id2",
                "id3"   # 每个文本片段一个ID
            ],
            "embeddings": [ ... ],  # 向量（可以不用管）
            "metadatas": [
                {"source": "blog_1001.txt", "blog_id": "1001"},
                {"source": "blog_1001.txt", "blog_id": "1001"},
            ],
            "documents": [
                "片段1文本...",
                "片段2文本..."
            ]
        }
        '''
        if old_docs["ids"]:
            self.chrom.delete(ids=old_docs["ids"])

        md5_hex = get_string_md5(data)
        if check_md5(md5_hex):
            return f"文件{filename}的md5值{md5_hex}已经被处理过了"

        if len(data) > config.max_split_char_number:
            #后续chrom的add_texts方法需要传入列表
            knowledge_chunk:list[str] = self.spliter.split_text(data)
        else:
            knowledge_chunk = [data]

        metadata = {
            "source":filename,
            "create_time":datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "blog_id": filename.replace("blog_", "").replace(".txt", "")  # 新增：标记博客ID，便于溯源
        }

        #内容加载到向量库
        self.chrom.add_texts(
            knowledge_chunk,
            metadatas=[metadata]*len(knowledge_chunk),  #要和列表对应
        )

        save_md5(md5_hex)
        return f"文件{filename}的md5值{md5_hex}已被处理并加载到向量库"

    # ========== 新增：专门处理博客+多评论的上传方法 ==========
    def upload_blog(self, blog_id: str, title: str, content: str, comments: list):
        """
        处理博客上传（适配多条评论）
        :param blog_id: 博客唯一ID
        :param title: 博客标题
        :param content: 博客内容
        :param comments: 评论列表（如["好吃！", "性价比高"]）
        :return: 上传结果
        """
        # 1. 保存/更新博客到txt文件
        txt_path, full_content = save_blog_to_txt(blog_id, title, content, comments)
        txt_filename = os.path.basename(txt_path)    # 提取文件名（包含路径）
        # 2. 调用原有方法上传向量库
        return self.upload_by_str(full_content, txt_filename)

if __name__ == '__main__':
    service = KnowledgeBaseService()
    # 测试多评论场景
    r = service.upload_blog(
        blog_id="1001",
        title="杭州美食推荐",
        content="西湖醋鱼是杭州的特色菜，口感酸甜",
        comments=["好吃！", "性价比一般", "环境不错"]
    )
    print(r)