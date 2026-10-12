"""서버 전체 500 장애 때 대기열이 FAILED 로 굳지 않는지 (FileErrorJudge)."""
from app.workers import FileErrorJudge


def test_server_wide_outage_blames_only_first_file():
    j = FileErrorJudge()
    # 모든 파일이 연달아 실패 → 첫 번째만 파일 탓
    results = [j.blame_file() for _ in range(10)]
    assert results == [True] + [False] * 9


def test_poison_file_blamed_each_time_after_other_success():
    j = FileErrorJudge()
    blames = 0
    for _ in range(3):
        if j.blame_file():   # 독 파일 실패
            blames += 1
        j.on_success()       # 다른 파일은 성공
    assert blames == 3


def test_recovery_after_outage():
    j = FileErrorJudge()
    j.blame_file()
    j.blame_file()       # 장애
    j.on_success()       # 서버 회복
    assert j.blame_file() is True
