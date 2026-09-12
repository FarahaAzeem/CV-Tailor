package com.cvtailor.repository;

import com.cvtailor.model.User;
import com.cvtailor.model.UserCv;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserCvRepository extends JpaRepository<UserCv, Long> {

    List<UserCv> findByUser(User user);

    Optional<UserCv> findByIdAndUser(Long id, User user);

    List<UserCv> findByTargetJobTitleContainingIgnoreCaseAndUser(String jobTitle, User user);
}
