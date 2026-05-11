'''
基于streamlit的文件上传应用
'''

import streamlit as st
from konwledge_base import KnowledgeBaseService


st.title('知识库更新服务')

upload_file = st.file_uploader(
    label='请上传txt文件',   # 上传文件的标签
    type=['txt'],    # 上传文件的类型,这里是只支持txt文件的上传
    accept_multiple_files=False        # 是否支持上传多个文件,这里是只支持一个文件的上传
)

if "service" not in st.session_state:   #这是一个st的字典，不会随着刷新而消失
    #保证 KnowledgeBaseService（向量库实例）在整个用户会话中只创建一次，所有上传的文件都存入同一个向量库
    st.session_state["service"] = KnowledgeBaseService()

if upload_file is not None:     # 检查是否上传了文件
    # 读取上传的文件内容
    file_name = upload_file.name
    file_size = upload_file.size / 1024  # 单位是KB
    file_type = upload_file.type

    st.subheader(f'文件名称: {file_name}')
    st.write(f'文件大小: {file_size:.2f} KB, 文件类型: {file_type}')

    #获取bytes文件,再转为utf-8字符串
    text = upload_file.getvalue().decode("utf-8")

     # 调用服务层的方法,将字符串加载到向量库
    result = st.session_state["service"].upload_by_str(text,file_name)
    #输出在页面
    st.write(result)


