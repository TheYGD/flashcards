package pl.jszmidla.flashcards.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import pl.jszmidla.flashcards.data.FlashcardSet;
import pl.jszmidla.flashcards.data.User;
import pl.jszmidla.flashcards.data.UsersActiveSet;
import pl.jszmidla.flashcards.data.dto.FlashcardResponse;
import pl.jszmidla.flashcards.data.dto.RememberedAndUnrememberedFlashcardsSplitted;
import pl.jszmidla.flashcards.data.exception.item.ItemNotFoundException;
import pl.jszmidla.flashcards.data.exception.item.UsersActiveSetNotFoundException;
import pl.jszmidla.flashcards.data.mapper.FlashcardMapper;
import pl.jszmidla.flashcards.repository.UsersActiveSetRepository;

import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class UsersActiveSetService {

    private UsersActiveSetRepository usersActiveSetRepository;
    private FlashcardSetService flashcardSetService;
    private UsersRecentSetService usersRecentSetService;
    private FlashcardMapper flashcardMapper;


    public UsersActiveSet getUsersActiveSet(FlashcardSet set, User user) {
        return usersActiveSetRepository.findByUserAndFlashcardSet(user, set)
                .orElseThrow(UsersActiveSetNotFoundException::new);
    }

    @Transactional
    public UsersActiveSet getOrCreateUsersActiveSet(FlashcardSet set, User user) {
        try {
            return getUsersActiveSet(set, user);
        } catch (ItemNotFoundException e) {
            UsersActiveSet usersActiveSet = new UsersActiveSet();
            usersActiveSet.setUser(user);
            usersActiveSet.setFlashcardSet(set);
            usersActiveSet.adjustExpirationDate();

            usersActiveSetRepository.save(usersActiveSet);
            return usersActiveSet;
        }
    }

    public Set<Long> getRememberedFlashcardsIds(FlashcardSet set, User user) {
        UsersActiveSet usersActiveSet = getOrCreateUsersActiveSet(set, user);

        if (usersActiveSet.getExpirationDate().isBefore( LocalDateTime.now() )) {
            usersActiveSet.adjustExpirationDate();
            usersActiveSet.clearRememberedFlashcards();
            usersActiveSetRepository.save(usersActiveSet);
        }

        Set<Long> rememberedFlashcardIdSet = mapCSVStringToSetOfLongs(usersActiveSet.getRememberedFlashcardsCSV());
        return rememberedFlashcardIdSet;
    }

    private Set<Long> mapCSVStringToSetOfLongs(String rememberedFlashcardsCSV) {
        String[] idsSet = rememberedFlashcardsCSV.split(",");
        // in case of no cards remembered
        if (idsSet[0].isBlank()) {
            return Set.of();
        }

        return Arrays.stream(idsSet)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
    }

    public void markFlashcardAsRemembered(long setId, long flashcardId, User user) {
        FlashcardSet set = flashcardSetService.findById(setId);

        UsersActiveSet usersActiveSet = getOrCreateUsersActiveSet(set, user);
        usersActiveSet.addFlashcardToRemembered(flashcardId);
        usersActiveSetRepository.save(usersActiveSet);
    }

    public void markSetAsCompleted(long setId, User user) {
        FlashcardSet set = flashcardSetService.findById(setId);

        UsersActiveSet usersActiveSet = getOrCreateUsersActiveSet(set, user);
        usersActiveSet.incrementExpirationInterval();
        usersActiveSetRepository.save(usersActiveSet);
    }

    /**
     * If user wants to run set sooner than it would reload itself
     */
    public void reloadSetSooner(long setId, User user) {
        FlashcardSet set = flashcardSetService.findById(setId);

        UsersActiveSet usersActiveSet = getOrCreateUsersActiveSet(set, user);
        usersActiveSet.adjustExpirationDate();
        usersActiveSet.clearRememberedFlashcards();

        usersActiveSetRepository.save(usersActiveSet);
    }

    public LocalDateTime getSetExpirationDate(long setId, User user) {
        FlashcardSet set = flashcardSetService.findById(setId);
        UsersActiveSet usersActiveSet = getOrCreateUsersActiveSet(set, user);

        return usersActiveSet.getExpirationDate();
    }

    public RememberedAndUnrememberedFlashcardsSplitted showSplittedFlashcardsToUser(Long id, User user) {
        FlashcardSet flashcardSet = flashcardSetService.findById(id);
        Set<Long> rememberedFlashcardsIds = getRememberedFlashcardsIds(flashcardSet, user);

        Map<Boolean, List<FlashcardResponse>> flashcardsSplittedMap = flashcardSet.getFlashcards().stream()
                .map(flashcardMapper::entityToResponse)
                .collect(Collectors.groupingBy( flashcard -> rememberedFlashcardsIds.contains( flashcard.getId() ) ));

        RememberedAndUnrememberedFlashcardsSplitted flashcardsSplitted = new RememberedAndUnrememberedFlashcardsSplitted();
        flashcardsSplitted.setRememberedFlashcardList( flashcardsSplittedMap.get(true) );
        flashcardsSplitted.setUnrememberedFlashcardList( flashcardsSplittedMap.get(false) );

        // add this set to user's recent seen
        usersRecentSetService.addRecentSetIfLogged(user, flashcardSet);

        return flashcardsSplitted;
    }
}
