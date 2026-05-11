import json
import os
from typing import Sequence

from langchain_core.chat_history import BaseChatMessageHistory
from langchain_core.messages import BaseMessage, message_to_dict, messages_from_dict

#工厂方法--专门用来造对象的方法。  传入参数--返回对象
def get_history(session_id):
    return FileChatMessageHistory(session_id, "./chat_history")

class FileChatMessageHistory(BaseChatMessageHistory):
    # 实现了BaseChatMessageHistory接口，所以可以使用add_messages、messages、clear方法。添加历史获取历史和清空
    def __init__(self, session_id: str, storage_path):  #初始化时，需要指定会话ID和存储路径
        self.session_id = session_id
        self.storage_path = storage_path
        self.file_path = os.path.join(self.storage_path, self.session_id)   #拼接文件路径

        #文件如果不存在则创建
        os.makedirs(os.path.dirname(self.file_path), exist_ok=True)
    '''
    添加历史记录：
    传入的messages是sequence，这是一个抽象类型，可以表示任何可迭代对象（列表、元组、集合、字符串等）,所以可以接收任何序列类型
    传入的messages是BaseMessage的子类，所以可以添加任何类型的消息,包括HumanMessage、AIMessage、SystemMessage等
    运行案例：
    self.messages（历史消息）：[HumanMessage(content="你好"), AIMessage(content="你好！")]
    messages（新消息）：[HumanMessage(content="天气如何？"), AIMessage(content="今天天气晴朗。")]
    all_messages（合并后消息）：[HumanMessage(content="你好"), AIMessage(content="你好！"), HumanMessage(content="天气如何？"), AIMessage(content="今天天气晴朗。")]
    因为里面每一个元素都是BaseMessage的子类对象，所以在和字典相互转换的时候可以识别
    '''
    def add_messages(self, messages: Sequence[BaseMessage]) -> None:
        all_messages = list(self.messages) #由于property吧方法变成属性，所以不需要加（）
        all_messages.extend(messages)   #在原有历史记录列表末尾加上新消息

        #将所有消息转换为字典添加到列表
        #消息对象无法存储在文件--所以需要转换为字典
        #列表内的每一个元素都是一个字典，字典的key是消息的type，value是消息的内容或元数据
        new_messages = [message_to_dict(msg) for msg in all_messages]
        #写入文件
        with open(self.file_path, "w",encoding="utf-8") as f:
            json.dump(new_messages,f)       #调用json模块的dump方法，将列表转换为json字符串，写入文件中
            #第一个参数--要写入的数据，第二个参数--文件对象


    @property   #把一个方法变成属性！！！
    def messages(self) -> list[BaseMessage]:
        try:
            with open(self.file_path, "r",encoding="utf-8") as f:
                messages_data = json.load(f)        #返回值是列表，要转换为消息对象列表
                #messages_from_dict能狗将列表里的每一个字典元素转化成消息对象
                messages = messages_from_dict(messages_data)    #同样的根据他们的type，转换为不同的消息对象，如HumanMessage、AIMessage、SystemMessage等
                return messages
        except FileNotFoundError:   #如果没有这个文件 ，返回空列表
                return []

    def clear(self) -> None:
        with open(self.file_path, "w",encoding="utf-8") as f:
            json.dump([],f)    #[]替换原本所有内容实现清楚