package io.github.atengk.cloud.sample.feign.model;

/**
 * 复杂 GET 查询 POJO 入参对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class UserQuery {

    private String keyword;
    private Integer page;
    private Integer size;

    public UserQuery() {
    }

    public UserQuery(String keyword, Integer page, Integer size) {
        this.keyword = keyword;
        this.page = page;
        this.size = size;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}
