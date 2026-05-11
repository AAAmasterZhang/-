import config_data as config
from langchain_chroma import Chroma

class VectorStoresService(object):
    def __init__(self,embedding):   #初始化方法，接受embedding模型
        self.embedding = embedding

        self.vector_stores = Chroma(     #初始化向量数据库
            collection_name=config.collection_name,  #集合名称
            embedding_function=embedding,  #嵌入函数
            persist_directory=config.persist_directory  #持久化目录，用于存储向量数据库
        )

    # 获取检索器  vector_stores->chroma->VectorStore，chroma实现了VectorStore接口，所以可以使用as_retriever方法获取检索器
    def get_retriever(self):
        return self.vector_stores.as_retriever(search_kwargs={"k": config.similarity_threshold})

# 返回VectorStoreRetriever（继承自 BaseRetriever）