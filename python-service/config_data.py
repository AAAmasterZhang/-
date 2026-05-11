
md5_path = './md5.text'

TXT_STORE_PATH = "./blog_txts"

#Chroma
collection_name = "rag4java"
persist_directory = "./chroma_db"

#split
chunk_size=1000   #分割后文本的最大长度
chunk_overlap=100   #分割后文本的重叠长度
separators=["\n\n", "\n", "。", "！", "？", "!", "?", " ", ""]    # 分割符
max_split_char_number = 1000    #文本分割的阈值，超过1000才进行分割


similarity_threshold = 2    # 检索返回的匹配数量

embedding_model_name = "text-embedding-v4"
chat_model_name = "qwen3-max"
