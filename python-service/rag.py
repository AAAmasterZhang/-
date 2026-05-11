import os

import jieba
from langchain_community.chat_models import ChatTongyi
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.retrievers import BM25Retriever
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.runnables import RunnablePassthrough, RunnableWithMessageHistory, RunnableLambda
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

from file_history_store import get_history
from vector_stores import VectorStoresService
import config_data as config





# --- 中文分词辅助函数 ---
def jieba_tokenize(text: str):
    """将中文文本切分为词列表，供 BM25 使用"""
    return list(jieba.cut(text))


def print_prompt(prompt):
    print("----")
    print(prompt.to_string())
    print("----")

    return prompt

def print_string(string):
    print("----")
    print("打印伪答案")
    print(string)
    print("----")
    return string

class RagService(object):
    #创建实例时自动调用
    def __init__(self):
        self.vector_service = VectorStoresService(embedding = DashScopeEmbeddings(model = config.embedding_model_name))
        self.prompt_template = ChatPromptTemplate.from_messages([
            ("system", "你是一个点评网站的推荐人，要为客户推荐餐厅,我现在会提供所以博客文章的内容，以为我提供的资料为主，简洁和专业的回答用户问题，返回最适合的博客id。参考资料：{context}"),
            ("system", "并且我提供用户的历史记录如下："),
            MessagesPlaceholder("history"),
            ("human", "用户提问：{input}"),
        ])
        self.chat_model = ChatTongyi(model_name = config.chat_model_name)

        # 初始化BM25检索器
        self.bm25_retriever = self._prepare_bm25_retriever()


        self.chain = self.__get_chain()

    #检索器的构建需要-文本，分词方式，top_k。这个函数主要就是读取博客文章并切分为合适的文本
    def _prepare_bm25_retriever(self):
        """
        手撕功能：提取 blog_txts 下的 txt 文件，分词并构建 BM25 库
        """
        all_docs = []
        # 确保路径正确，os.path.dirname(__file__) 获取当前文件所在目录，再拼接 blog_txts 目录
        blog_dir = os.path.join(os.path.dirname(__file__), "blog_txts")

        if not os.path.exists(blog_dir):
            print(f"警告：找不到目录 {blog_dir}，请检查路径")
            # 如果没找到文件，返回一个空的模拟检索器防止报错
            return BM25Retriever.from_documents([Document(page_content="empty")], preprocess_func=jieba_tokenize)

        print(f"正在扫描目录: {blog_dir} ...")

        for file_name in os.listdir(blog_dir):
            if file_name.endswith(".txt"):  # 只处理 txt 文件
                file_path = os.path.join(blog_dir, file_name)
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        content = f.read()
                        #封装为Document对象，包含文件名作为元数据
                        if content.strip():
                            all_docs.append(Document(page_content=content, metadata={"source": file_name}))
                except Exception as e:
                    print(f"读取文件 {file_name} 出错: {e}")

        # 文档切分
        text_splitter = RecursiveCharacterTextSplitter(chunk_size=400, chunk_overlap=40)
        split_docs = text_splitter.split_documents(all_docs)

        # 【关键点】使用 preprocess_func 指定中文分词逻辑
        # 这样在创建索引和后续查询时，都会自动调用 jieba 进行分词匹配
        retriever = BM25Retriever.from_documents(
            split_docs,
            preprocess_func=jieba_tokenize
        )
        retriever.k = 5  # 初步召回稍多一点，留给 Rerank 筛选
        return retriever

        # --- 【核心手撕】RRF 混合检索算法 ---
    def _manual_rrf_hybrid_retrieve(self, query: str):
            """
            纯数学实现 RRF 排序。
            规则：Score = Σ 1 / (60 + rank)
            """
            print(f"--- [RRF 混合检索启动] 查询: {query} ---")

            # 1. 获取两路结果，返回的是list[Document]
            bm25_docs = self.bm25_retriever.invoke(query)
            vector_docs = self.vector_service.get_retriever().invoke(query)

            # 2. RRF 算法打分
            k = 60  # RRF 标准常数
            rrf_scores = {}

            # 辅助字典：通过内容找回 Document 对象（保留元数据）
            doc_map = {}

            # 计算 BM25 路的分数
            for rank, doc in enumerate(bm25_docs):
                content = doc.page_content  # 提取文档内容
                doc_map[content] = doc  # 存储文档对象=={"文档内容": Document对象}
                #{文档内容: 分数}  get(content, 0)表示如果content不在rrf_scores中，返回0
                rrf_scores[content] = rrf_scores.get(content, 0) + 1.0 / (k + rank + 1)

            # 计算 向量检索 路的分数
            for rank, doc in enumerate(vector_docs):
                content = doc.page_content
                doc_map[content] = doc
                #再原本的基础上加上向量检索的分数
                rrf_scores[content] = rrf_scores.get(content, 0) + 1.0 / (k + rank + 1)

            # 3. 排序并取前 3 名
            # 排序规则：按 rrf_scores 的值倒序排
            sorted_items = sorted(rrf_scores.items(), key=lambda x: x[1], reverse=True)

            final_docs = []
            for i, (content, score) in enumerate(sorted_items[:3]):
                print(f"[RRF 排名-{i + 1}] 分数: {score:.4f} | 来源: {doc_map[content].metadata.get('source')}")
                final_docs.append(doc_map[content]) #取出来的是Document对象

            return final_docs


    def __get_chain(self):
        """获取最终的执行链"""
        #获取向量库的检索器
        retriever = self.vector_service.get_retriever()

        #格式化函数，打印检索到的文档
        #输入list[Document],输出str
        def format_func(docs: list[Document]):
            if not docs:
                return "无相关资料"
            formatted_str = ""
            for doc in docs:
                formatted_str += f"文档片段:{doc.page_content}\n文档元数据:{doc.metadata['source']}\n"
            return formatted_str

        # 定义第一个lambda函数，用于从输入值中提取"input"键对应的值
        def temp1(value: dict) -> str:
            return value["input"]

        # 定义第二个lambda函数，用于从输入值中提取"context"和"history"键对应的值
        def temp2(value):
            new_value = {}
            new_value["input"] = value["input"]["input"]
            new_value["context"] = value["context"]
            new_value["history"] = value["input"]["history"]
            return new_value

        # A. 检索步骤：直接调用手写的 RRF 逻辑
        # 这里的输入是 query 字符串，输出是格式化后的 context 字符串
        retrieval_chain = (
                    RunnableLambda(self._manual_rrf_hybrid_retrieve)
                    | format_func
            )

        # B. 完整的业务逻辑链
        chain = (
                    {
                        "input": RunnablePassthrough(),  # 保持原始 input 字典
                        # 从 input 字典里提取 "input" 字符串去跑检索链
                        "context": RunnableLambda(temp1) | retrieval_chain
                    }
                    | RunnableLambda(temp2)
                    | self.prompt_template
                    | print_prompt
                    | self.chat_model
                    | StrOutputParser()
            )

        # 尝试加入query transformation
        '''
        
        #第一个 HyDE (假设性文档/预演变换) 
        #数据流向：相当于是新增一个问询ai功能，获取输入的字符串，ai返回aimessage，然后再转为字符串
        
        hyde_prompt = ChatPromptTemplate.from_template(
            "请针对用户的问题，虚构一段专业的餐厅点评或推荐语，用于后续搜索：{input}")
        # --- 入链 ---
        chain = (
                {
                    "input": RunnablePassthrough(),
                    "context": RunnableLambda(temp1)
                               | hyde_prompt  | self.chat_model | StrOutputParser() | print_string   # 生成“伪答案”
                               | retriever | format_func  # 用伪答案去检索
                }
                | RunnableLambda(temp2) | self.prompt_template | print_prompt | self.chat_model | StrOutputParser()
        )
        '''

        '''
        #第二个Multi-Query (多角度提问并行方案)
        #数据流向：接收输入的input，查询ai，返回aimessage包含三个问题的字符串，将三个问题切割为列表，分别检索向量库，返回检索结果的列表
        
        # 1. 定义生成3个提问的 Prompt  
        mq_prompt = ChatPromptTemplate.from_template(
            "你是一个餐厅推荐专家。请将用户问题改写为3个侧重点不同的完整搜索问题（例如从：口味、环境、性价比角度），每行一个。问题：{input}"
        )

        # 2. 定义处理 3 个问题并检索的函数
        def multi_retrieve(queries_str: str):
            print("----[Multi-Query 改写结果]----")
            print(queries_str)
            print("----------------------------")
            # 解析并检索
            queries = [q.strip() for q in queries_str.split("\n") if q.strip()][:3]
            all_docs = []
            for q in queries:
                all_docs.extend(retriever.invoke(q))
            return all_docs

        chain = (
                {
                    "input": RunnablePassthrough(),
                    "context": RunnableLambda(temp1)
                               | mq_prompt | self.chat_model | StrOutputParser()
                               | RunnableLambda(multi_retrieve) | format_func
                }
                | RunnableLambda(temp2) | self.prompt_template | print_prompt | self.chat_model | StrOutputParser()
        )
        '''

        '''
        #第三个Query Rewriting(对话历史改写方案)
        #数据流向：接收输入的input,从里面提取input和history两个字段封装，查询ai，返回aimessage，解析为字符串再给向量检索器
        
        # 1. 定义改写 Prompt
        rewrite_prompt = ChatPromptTemplate.from_messages([
            ("system", "参考对话历史，将用户最新的模糊提问重写为一个独立的、包含具体搜索目标的完整搜索词，比如记录用户提问的地址时间或是一些具体的搜索关键词，用于替换用户最新提问中的代词。"),
            MessagesPlaceholder("history"),
            ("human", "{input}")
        ])

        # 2. 定义打印改写后搜索词的函数
        def print_rewrite(string):
            print("----[改写后的独立搜索词]----")
            print(string)
            print("--------------------------")
            return string

        chain = (
                {
                    "input": RunnablePassthrough(),
                    "context": (lambda x: {"input": x["input"], "history": x["history"]})  # 必须同时传入 input 和 history
                               | rewrite_prompt | self.chat_model | StrOutputParser() | RunnableLambda(print_rewrite)
                               | retriever | format_func
                }
                | RunnableLambda(temp2) | self.prompt_template | print_prompt | self.chat_model | StrOutputParser()
        )
        '''

        '''
        #第四个Step-Back Prompting (后退一步检索方案)
        #数据流向：接收输入的input，查询ai，返回aimessage，解析为字符串，再给向量检索器，返回两条检索结果的列表

        # 1. 定义后退一步（抽象化）的 Prompt
        sb_prompt = ChatPromptTemplate.from_template(
            "针对用户的具体推荐请求，请提出一个更基础、更广泛的背景原理问题。具体请求：{input}"
        )

        # 2. 定义双重检索逻辑
        def step_back_retrieve_logic(value):
            # 提取具体问题
            q_original = value
            # 生成后退一步的问题
            q_back = (sb_prompt | self.chat_model | StrOutputParser()).invoke({"input": q_original})

            print(f"----[Step-Back 后退问题]----\n{q_back}\n--------------------------")

            # 检索原问题文档 + 检索背景问题文档
            docs_specific = retriever.invoke(q_original)    #invoke方法，返回检索结果的列表
            docs_general = retriever.invoke(q_back)
            return docs_specific + docs_general

        chain = (
                {
                    "input": RunnablePassthrough(),
                    "context": RunnableLambda(temp1)
                               | RunnableLambda(step_back_retrieve_logic)  # 内部处理双检索
                               | format_func
                }
                | RunnableLambda(temp2) | self.prompt_template | print_prompt | self.chat_model | StrOutputParser()
        )
        
        '''


        # chain = (
        #         {"input": RunnablePassthrough(),    #直接获取原始的input，记录input确保不丢失
        #          "context": RunnableLambda(temp1) | retriever | format_func     #根据input检索向量库，格式化输出字符串
        #          } | RunnableLambda(temp2)| self.prompt_template | print_prompt | self.chat_model | StrOutputParser()
        # )

        # 定义RunnableWithMessageHistory，用于处理消息历史记录
        conversation_chain = RunnableWithMessageHistory(
            chain,
            get_history,    #工厂方法
            input_messages_key="input",
            history_messages_key="history",
        )


        return conversation_chain

if __name__ == '__main__':
    session_config = {
        "configurable": {
            "session_id": "320",
        }
    }

    # res = RagService().chain.invoke("体重130尺码推荐")

    #增强链的invoke要的是字典不是字符串
    res = RagService().chain.invoke({"input": "周末和朋友去运动"}, session_config)

    print(res)

