import base64
from zipfile import ZipFile
from io import BytesIO
from AES import decrypt
from Structure import getStructure, Reader
from typing import Any, Dict

def get_saves(zip_base64: str) -> Dict[str, Dict[str, Any]]:
    """
    解码 Base64 并解压缩和解密存档数据喵。

    参数:
        zip_base64 (str): Base64 编码的 ZIP 存档数据喵。

    返回:
        Dict[str, Dict[str, Any]]: 解析后的存档数据喵。
    """
    save_dict = {}

    # 解码 Base64 字符串
    zip_data = base64.b64decode(zip_base64)

    # 使用 BytesIO 处理字节数据
    with ZipFile(BytesIO(zip_data)) as zip_file:
        # 遍历压缩包内的文件
        for file_info in zip_file.filelist:
            filename = file_info.filename
            with zip_file.open(filename) as f:
                save_dict[filename] = f.read()  # 读取文件数据

    file_head = {key: value[0].to_bytes() for key, value in save_dict.items()}
    structure_list = getStructure(file_head)

    for key, value in save_dict.items():
        save_dict[key] = decrypt(value[1:])
        reader = Reader(save_dict[key])
        save_dict[key] = reader.parseStructure(structure_list[key])

    return save_dict

