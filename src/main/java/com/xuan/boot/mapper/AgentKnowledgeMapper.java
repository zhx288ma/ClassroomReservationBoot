package com.xuan.boot.mapper;

import com.xuan.boot.domain.AgentKnowledgeChunk;
import com.xuan.boot.domain.AgentKnowledgeDocument;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentKnowledgeMapper {
    @Select("select count(1) from tb_agent_knowledge_document where status=1")
    int countActiveDocuments();

    @Insert("insert into tb_agent_knowledge_document(title, category, source_type, source_file_name, source_file_path, content, content_hash, status, index_status, created_by) values(#{title}, #{category}, #{sourceType}, #{sourceFileName}, #{sourceFilePath}, #{content}, #{contentHash}, #{status}, #{indexStatus}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDocument(AgentKnowledgeDocument document);

    @Select("select * from tb_agent_knowledge_document where status=1 order by update_time desc limit #{limit}")
    List<AgentKnowledgeDocument> listDocuments(@Param("limit") Integer limit);

    @Select("select * from tb_agent_knowledge_document where status=1 order by id asc")
    List<AgentKnowledgeDocument> listAllActiveDocuments();

    @Select("select * from tb_agent_knowledge_document where id=#{id} and status=1")
    AgentKnowledgeDocument findActiveById(@Param("id") Long id);

    @Update("update tb_agent_knowledge_document set status=0, index_status='REMOVED', update_time=now() where id=#{id}")
    int deactivateDocument(@Param("id") Long id);

    @Update("update tb_agent_knowledge_document set index_status=#{indexStatus}, chunk_count=#{chunkCount}, vector_count=#{vectorCount}, last_indexed_at=now(), last_index_error=#{lastIndexError}, update_time=now() where id=#{id}")
    int updateIndexState(AgentKnowledgeDocument document);

    @Delete("delete from tb_agent_knowledge_chunk where document_id=#{documentId}")
    int deleteChunksByDocumentId(Long documentId);

    @Insert("insert into tb_agent_knowledge_chunk(document_id, chunk_index, content, keywords, embedding_json) values(#{documentId}, #{chunkIndex}, #{content}, #{keywords}, #{embeddingJson})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertChunk(AgentKnowledgeChunk chunk);

    @Select("select c.*, d.title, d.category from tb_agent_knowledge_chunk c join tb_agent_knowledge_document d on c.document_id=d.id where d.status=1 order by c.id asc limit #{limit}")
    List<AgentKnowledgeChunk> listActiveChunks(@Param("limit") Integer limit);

    @Select("select content from tb_agent_knowledge_chunk where id=#{chunkId}")
    String findChunkContent(@Param("chunkId") Long chunkId);

    @Select("select c.*, d.title, d.category from tb_agent_knowledge_chunk c "
            + "join tb_agent_knowledge_document d on c.document_id=d.id "
            + "where d.status=1 and d.category=#{category} order by c.id asc limit #{limit}")
    List<AgentKnowledgeChunk> listActiveChunksByCategory(@Param("category") String category,
                                                          @Param("limit") Integer limit);
}
