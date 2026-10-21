package com.shtbs.interview.mapper;
import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.dto.UserUpdateDTO;
import com.shtbs.interview.entity.User;
import com.shtbs.interview.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {
    User selectById(@Param("id")Long id);//根据id查询
    int insert(User user);//新增用户
    // 3. 查询所有用户
    List<UserVO> selectAll(@Param("offset") Integer offset,
                           @Param("size") Integer size);

    // 4. 根据用户名查询
    User selectByUsername(@Param("username") String username);

    // 5. 修改用户
    int update(User user);

    // 6. 根据 id 删除
    int deleteById(@Param("id")Long id);

    Long countUsers();

}
