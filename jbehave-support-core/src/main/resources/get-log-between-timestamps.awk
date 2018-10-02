#!/usr/bin/awk -f

BEGIN {
  startTimestamp = ARGV[1];
  endTimestamp = ARGV[2];
  timestampRegex = ARGV[3];
  inputFile = ARGV[4];

  # delete non-file arguments
  delete ARGV[1];
  delete ARGV[2];
  delete ARGV[3];
}

# only timestamp lines in given timestamp range
$1 FS $2 >= startTimestamp && $1 FS $2 <= endTimestamp {
  print $0;

  # use different pointer to inputFile than the current one and read all lines until we hit current line number
  for (i = 0; i < FNR; i++) {
    getline line < inputFile;
  }

  # read and print all lines until we hit another timestamp line
  getline line < inputFile;
  while (match(line, timestampRegex) != 1) {
    print line;
    # when we hit EOF, break
    if (getline line < inputFile == 0) {
      break;
    }
  }

  close(inputFile);
}
